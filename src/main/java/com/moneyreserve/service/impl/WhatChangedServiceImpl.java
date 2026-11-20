package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.dto.WhatChangedAvailability;
import com.moneyreserve.dto.WhatChangedResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.InsightsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What changed?" (Premium Insights, V1.28.0): recorded spending per category in one period
 * against the period before, ranked by the size of the change.
 *
 * <p>Access needs both the global switch (budget policy, published from the Admin Dashboard) and
 * the customer's Premium entitlement ({@link PremiumEntitlements}); every endpoint checks both.
 *
 * <p>Spending is counted by {@link BiTransactionRepository#COMPARABLE_SPEND}, the same rules the
 * category drilldown uses for both periods, over every matching row (never a page). Computed on
 * each request from the transactions, so an edit, a recategorisation or an import shows at once;
 * nothing is stored. Facts only: amounts, counts and dates, never a reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatChangedServiceImpl {

  public static final String FEATURE_KEY = "insights.what-changed";
  public static final String DRILLDOWN_PATH = "/api/insights/breakdown/{categoryId}/transactions";
  static final int TOP = 3;

  private final InsightsProperties properties;
  private final BudgetPolicyProvider policy;
  private final PremiumEntitlements entitlements;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiAccountRepository accounts;
  private final InsightsMapper mapper;

  // ---------------------------------------------------------------------------------
  // Access
  // ---------------------------------------------------------------------------------

  public WhatChangedAvailability availability(UUID userId) {
    boolean enabled = policy.current().whatChanged();
    boolean entitled = entitlements.hasPremium(userId, today());
    String reason = !enabled ? "FEATURE_DISABLED" : !entitled ? "PREMIUM_REQUIRED" : null;
    return WhatChangedAvailability.builder()
        .featureKey(FEATURE_KEY)
        .enabled(enabled)
        .entitled(entitled)
        .available(enabled && entitled)
        .reason(reason)
        .build();
  }

  /** Refuses unless the switch is on and this customer is entitled (switch checked first). */
  public void requireAccess(UUID userId) {
    if (!policy.current().whatChanged()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_WHAT_CHANGED_DISABLED);
    }
    if (!entitlements.hasPremium(userId, today())) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_WHAT_CHANGED_PREMIUM);
    }
  }

  // ---------------------------------------------------------------------------------
  // The comparison
  // ---------------------------------------------------------------------------------

  /** The two periods compared for {@code month} on {@code today}; null current range on the 1st. */
  record Periods(String mode, LocalDate curStart, LocalDate curEnd, LocalDate prevStart, LocalDate prevEnd) {}

  /**
   * This month: its completed days (through yesterday) against the same dates last month, both
   * capped to the days both months have (31 March → 28 days on each side in a common year). A
   * completed month: all of it against all of the month before. The 1st: no completed days.
   */
  static Periods periods(YearMonth month, LocalDate today) {
    YearMonth thisMonth = YearMonth.from(today);
    if (month.isAfter(thisMonth)) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_WHAT_CHANGED_PERIOD);
    }
    YearMonth before = month.minusMonths(1);
    if (month.equals(thisMonth)) {
      int completed = today.getDayOfMonth() - 1;
      if (completed == 0) {
        return new Periods("MONTH_TO_DATE", null, null, null, null);
      }
      int days = Math.min(completed, before.lengthOfMonth());
      return new Periods("MONTH_TO_DATE", month.atDay(1), month.atDay(days), before.atDay(1), before.atDay(days));
    }
    return new Periods("COMPLETED_MONTH", month.atDay(1), month.atEndOfMonth(), before.atDay(1), before.atEndOfMonth());
  }

  @Transactional(readOnly = true)
  public WhatChangedResponse compare(UUID userId, YearMonth requested, List<UUID> accountIds) {
    requireAccess(userId);
    LocalDate today = today();
    YearMonth month = requested == null ? YearMonth.from(today) : requested;
    List<UUID> chosen = ownedAccounts(userId, accountIds);
    boolean all = chosen.isEmpty();
    Collection<UUID> ids = all ? List.of(new UUID(0L, 0L)) : chosen; // never an empty IN list
    String currency = properties.getCurrency();

    Periods p = periods(month, today);
    WhatChangedResponse.WhatChangedResponseBuilder out =
        WhatChangedResponse.builder()
            .mode(p.mode())
            .month(month.toString())
            .currency(currency)
            .accountIds(chosen)
            .changes(List.of());
    if (p.curStart() == null) {
      return out.status("NO_COMPLETED_DAYS").build();
    }
    out.currentPeriod(range(p.curStart(), p.curEnd())).previousPeriod(range(p.prevStart(), p.prevEnd()));

    Map<UUID, BigDecimal[]> current = aggregate(userId, p.curStart(), p.curEnd(), currency, all, ids);
    Map<UUID, BigDecimal[]> previous = aggregate(userId, p.prevStart(), p.prevEnd(), currency, all, ids);
    LocalDate earliest = transactions.earliestRecordedOn(userId, all, ids);
    boolean covered = earliest != null && !earliest.isAfter(p.prevStart());
    long otherCurrency =
        transactions.countOtherCurrencySpend(userId, p.prevStart(), p.prevEnd(), currency, all, ids)
            + transactions.countOtherCurrencySpend(userId, p.curStart(), p.curEnd(), currency, all, ids);
    out.coverage(
        WhatChangedResponse.Coverage.builder()
            .earliestRecordOn(earliest)
            .previousPeriodCovered(covered)
            .currentTransactionCount(count(current))
            .previousTransactionCount(count(previous))
            .otherCurrencyExcludedCount(otherCurrency)
            .recordedTransactionsOnly(true)
            .build());
    if (!covered) {
      // No records reaching back to the previous period: that is missing history, not "spent
      // nothing then", so nothing is compared.
      return out.status("INSUFFICIENT_HISTORY").build();
    }

    Set<UUID> all2 = new LinkedHashSet<>(current.keySet());
    all2.addAll(previous.keySet());
    Map<UUID, BiCategory> byId = new HashMap<>();
    categories.findAllById(all2).forEach(c -> byId.put(c.getId(), c));
    List<WhatChangedResponse.Change> ranked = new ArrayList<>();
    List<BigDecimal> sizes = new ArrayList<>();
    for (UUID categoryId : all2) {
      BigDecimal[] now = current.getOrDefault(categoryId, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      BigDecimal[] then = previous.getOrDefault(categoryId, new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      BigDecimal diff = now[0].subtract(then[0]);
      if (diff.signum() == 0) {
        continue; // both zero, or no change: not a change
      }
      ranked.add(change(categoryId, byId.get(categoryId), now, then, diff, p, currency, chosen));
      sizes.add(diff.abs());
    }
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < ranked.size(); i++) {
      order.add(i);
    }
    order.sort(
        Comparator.<Integer, BigDecimal>comparing(sizes::get)
            .reversed()
            .thenComparing(i -> sortOrder(byId.get(ranked.get(i).getCategoryId())))
            .thenComparing(i -> ranked.get(i).getCategoryId().toString()));
    List<WhatChangedResponse.Change> top = order.stream().limit(TOP).map(ranked::get).toList();
    log.info("Insights what-changed userId={} month={} mode={} changes={}", userId, month, p.mode(), ranked.size());
    return out.status(top.isEmpty() ? "NO_CHANGES" : "AVAILABLE").changes(top).build();
  }

  private WhatChangedResponse.Change change(
      UUID categoryId,
      BiCategory c,
      BigDecimal[] now,
      BigDecimal[] then,
      BigDecimal diff,
      Periods p,
      String currency,
      List<UUID> accountIds) {
    boolean refund = now[0].signum() < 0 || then[0].signum() < 0;
    String state =
        refund ? "NET_REFUND" : then[0].signum() == 0 ? "NEW_THIS_PERIOD" : now[0].signum() == 0 ? "NONE_THIS_PERIOD" : "CHANGED";
    BigDecimal pct =
        refund || then[0].signum() <= 0
            ? null
            : diff.multiply(BigDecimal.valueOf(100)).divide(then[0], 1, RoundingMode.HALF_UP);
    return WhatChangedResponse.Change.builder()
        .categoryId(categoryId)
        .code(c == null ? null : c.getCode())
        .name(c == null ? null : c.getNameEn())
        .nameSi(c == null ? null : c.getNameSi())
        .nameTa(c == null ? null : c.getNameTa())
        .iconKey(c == null ? null : c.getIconKey())
        .colourHex(c == null ? null : c.getColourHex())
        .state(state)
        .current(side(now, refund))
        .previous(side(then, refund))
        .difference(mapper.money(diff))
        .direction(diff.signum() > 0 ? "INCREASE" : "DECREASE")
        .percentChange(pct)
        .paymentCountDifference(now[1].longValue() - then[1].longValue())
        .currentTransactions(drill(categoryId, p.curStart(), p.curEnd(), currency, accountIds))
        .previousTransactions(drill(categoryId, p.prevStart(), p.prevEnd(), currency, accountIds))
        .build();
  }

  private WhatChangedResponse.Side side(BigDecimal[] v, boolean refund) {
    BigDecimal total = v[0];
    long count = v[1].longValue();
    String average =
        !refund && total.signum() > 0 && count > 0
            ? mapper.money(total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP))
            : null;
    return WhatChangedResponse.Side.builder().total(mapper.money(total)).paymentCount(count).averagePayment(average).build();
  }

  private static WhatChangedResponse.Drilldown drill(
      UUID categoryId, LocalDate from, LocalDate to, String currency, List<UUID> accountIds) {
    return WhatChangedResponse.Drilldown.builder()
        .path(DRILLDOWN_PATH.replace("{categoryId}", categoryId.toString()))
        .categoryId(categoryId)
        .from(from)
        .to(to)
        .currency(currency)
        .accountIds(accountIds)
        .build();
  }

  /** {@code categoryId → [total, count]} over every matching row. */
  private Map<UUID, BigDecimal[]> aggregate(
      UUID userId, LocalDate from, LocalDate to, String currency, boolean all, Collection<UUID> ids) {
    Map<UUID, BigDecimal[]> out = new HashMap<>();
    for (Object[] row : transactions.aggregateComparableSpend(userId, from, to, currency, all, ids)) {
      BigDecimal total = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      long count = row[2] == null ? 0L : ((Number) row[2]).longValue();
      out.put((UUID) row[0], new BigDecimal[] {total.setScale(2, RoundingMode.HALF_UP), BigDecimal.valueOf(count)});
    }
    return out;
  }

  private static long count(Map<UUID, BigDecimal[]> m) {
    return m.values().stream().mapToLong(v -> v[1].longValue()).sum();
  }

  /** The customer's own accounts, as asked; any other id is refused (404, never someone else's). */
  public List<UUID> ownedAccounts(UUID userId, List<UUID> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return List.of();
    }
    List<UUID> out = new ArrayList<>(new LinkedHashSet<>(accountIds));
    for (UUID id : out) {
      if (id == null || accounts.findByIdAndUserIdAndDeletedAtIsNull(id, userId).isEmpty()) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND);
      }
    }
    return out;
  }

  private static WhatChangedResponse.Range range(LocalDate from, LocalDate to) {
    return WhatChangedResponse.Range.builder()
        .startDate(from)
        .endDate(to)
        .days((int) ChronoUnit.DAYS.between(from, to) + 1)
        .build();
  }

  private static int sortOrder(BiCategory c) {
    return c == null || c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder();
  }

  private LocalDate today() {
    return LocalDate.now(properties.zoneId());
  }
}
