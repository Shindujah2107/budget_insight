package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiBudget;
import com.moipay.budgetinsights.domain.BiBudgetAlert;
import com.moipay.budgetinsights.domain.BiBudgetCategoryLimit;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BudgetAlertType;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.BudgetAlertDto;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiBudgetAlertRepository;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiBudgetRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.BudgetAlertService;
import com.moipay.budgetinsights.service.InsightsMapper;
import com.moipay.budgetinsights.service.calc.BudgetMath;
import com.moipay.budgetinsights.service.calc.CapState;
import com.moipay.budgetinsights.service.calc.InsightsPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Threshold alerts (BI-25).
 *
 * <p>Three design points, each of which is the answer to a question that has a tempting wrong
 * answer.
 *
 * <p><b>It runs in the writer's transaction, not its own.</b> The compliance service uses
 * {@code REQUIRES_NEW} so an incident survives the rejection that caused it. The opposite is needed
 * here: an alert must see the transactions that pushed the category over its cap, and those rows
 * are uncommitted at the moment of evaluation. A new transaction would read the ledger as it was
 * before the batch and raise nothing.
 *
 * <p><b>It never throws into its caller.</b> A failure to record an alert must not turn a correctly
 * ingested batch into a failed request. The customer's money is recorded either way; the alert is
 * the lesser of the two.
 *
 * <p><b>Idempotency is a pre-read plus a unique index, in that order.</b> The pre-read is what
 * normally prevents a second alert; the index is what makes it true under a race. Two writes
 * crossing the same threshold at the same instant will collide, and that collision surfaces to the
 * caller — for ingest, through the retry it already has, which finds the alert on the second pass
 * and raises nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetAlertServiceImpl implements BudgetAlertService {

  private final InsightsProperties properties;
  private final BudgetPolicyProvider policy;
  private final InsightsMapper mapper;
  private final BiBudgetAlertRepository alerts;
  private final BiBudgetRepository budgets;
  private final BiBudgetCategoryLimitRepository limits;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;

  // ---------------------------------------------------------------------------------
  // Raise
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void evaluate(Caller caller, LocalDate onDate) {
    if (!properties.getAlerts().isEnabled() || onDate == null) {
      return;
    }
    try {
      raise(caller, onDate);
    } catch (DataIntegrityViolationException ex) {
      log.info(
          "Insights alert already raised concurrently userId={} on {}; letting the caller retry",
          caller.userId(),
          onDate);
      // NOT swallowed, and this is the one exception to the rule below. A unique-index collision
      // means another writer raised the same alert between this one's pre-read and its insert. The
      // session is poisoned either way, so catching it here would only delay the failure to the
      // caller's flush with the cause thrown away. Letting it out gives ingest's existing
      // retry-once the chance to re-run, find the alert on its pre-read, and raise nothing. On the
      // manual path it surfaces as a 409; the client retries with the same clientRef, the
      // transaction is written again, and the second evaluation finds the alert already there.
      throw ex;
    } catch (RuntimeException ex) {
      // Everything else is swallowed and logged at ERROR. A failure to record an alert must not
      // turn a correctly recorded transaction into a failed request: the customer's money is in
      // the ledger either way, and the alert is the lesser of the two.
      log.error(
          "Insights alert evaluation failed for userId={} on {}", caller.userId(), onDate, ex);
    }
  }

  private void raise(Caller caller, LocalDate onDate) {
    UUID userId = caller.userId();
    // ofMonth, not resolve(onDate, onDate, ...): resolve with both bounds given returns exactly
    // that range, so passing the transaction date twice would compare one day's spend against a
    // monthly cap -- which would almost never fire -- and would key the alert on the transaction
    // date rather than the month start, so it would never match bi_budget.period_start either.
    InsightsPeriod period = InsightsPeriod.ofMonth(YearMonth.from(onDate));
    LocalDate periodStart = period.start();

    Optional<BiBudget> found = budgets.findByUserIdAndPeriodStart(userId, periodStart);
    if (found.isEmpty()) {
      // No target, nothing to cross. A customer who has not set a budget has not asked to be told
      // anything about one.
      return;
    }
    BiBudget budget = found.get();

    Set<String> already = new HashSet<>();
    for (BiBudgetAlert raised : alerts.findAllByUserIdAndPeriodStart(userId, periodStart)) {
      already.add(key(raised.getCategoryId(), raised.getAlertType()));
    }

    List<BiBudgetAlert> toRaise = new ArrayList<>();
    // Which alert types are raised, and the default threshold, come from the budget policy
    // (Admin Dashboard). A type that is off raises nothing new; alerts already raised are kept.
    AdminBudgetPolicyState rules = policy.current();

    // ---- the overall target
    BigDecimal spentTotal = orZero(transactions.sumSpendBetween(userId, period.start(), period.end()));
    if (rules.alertBudgetExceeded()
        && budget.getOverallLimit() != null
        && budget.getOverallLimit().signum() > 0
        && spentTotal.compareTo(budget.getOverallLimit()) >= 0
        && already.add(key(null, BudgetAlertType.BUDGET_EXCEEDED))) {
      toRaise.add(
          alert(caller, periodStart, null, BudgetAlertType.BUDGET_EXCEEDED, null, spentTotal,
              budget.getOverallLimit()));
    }

    // ---- the per-category caps
    List<BiBudgetCategoryLimit> caps = limits.findAllByBudgetId(budget.getId());
    if (!caps.isEmpty()) {
      Map<UUID, BigDecimal> spendByCategory = new HashMap<>();
      for (Object[] row :
          transactions.aggregateSpendByCategoryBetween(userId, period.start(), period.end())) {
        spendByCategory.put((UUID) row[0], orZero((BigDecimal) row[1]));
      }

      for (BiBudgetCategoryLimit cap : caps) {
        BigDecimal spent = spendByCategory.getOrDefault(cap.getCategoryId(), BigDecimal.ZERO);
        CapState state =
            BudgetMath.capState(
                spent,
                cap.getLimitAmount(),
                cap.getAlertThresholdPct(),
                (short) rules.defaultAlertPct());
        if (state == null) {
          continue;
        }
        BudgetAlertType type =
            state == CapState.EXCEEDED
                ? BudgetAlertType.CATEGORY_EXCEEDED
                : BudgetAlertType.CATEGORY_APPROACHING;
        boolean typeOn =
            type == BudgetAlertType.CATEGORY_EXCEEDED
                ? rules.alertCategoryExceeded()
                : rules.alertCategoryApproaching();
        if (!typeOn || !already.add(key(cap.getCategoryId(), type))) {
          continue;
        }
        // An EXCEEDED alert does not carry a threshold: it fires at the cap itself, not at a
        // configurable share of it, and recording 80 beside an amount that passed 100% would read
        // as a mistake.
        Short thresholdPct =
            type == BudgetAlertType.CATEGORY_APPROACHING
                ? (cap.getAlertThresholdPct() == null
                    ? (short) rules.defaultAlertPct()
                    : cap.getAlertThresholdPct())
                : null;
        toRaise.add(
            alert(caller, periodStart, cap.getCategoryId(), type, thresholdPct, spent,
                cap.getLimitAmount()));
      }
    }

    if (toRaise.isEmpty()) {
      return;
    }
    alerts.saveAll(toRaise);
    // Counts and types only -- no amount, no category name. The alert row holds the detail; the
    // log says that something was raised, which is what an operator needs.
    log.info(
        "Insights alerts raised userId={} period={} count={}", userId, periodStart, toRaise.size());
  }

  // ---------------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public List<BudgetAlertDto> forPeriod(UUID userId, LocalDate onDate) {
    LocalDate anchor = onDate != null ? onDate : LocalDate.now(properties.zoneId());
    LocalDate periodStart = InsightsPeriod.ofMonth(YearMonth.from(anchor)).start();

    List<BiBudgetAlert> raised =
        alerts.findAllByUserIdAndPeriodStartOrderByRaisedAtDesc(userId, periodStart);
    log.info(
        "Insights alerts read userId={} period={} count={}", userId, periodStart, raised.size());
    if (raised.isEmpty()) {
      return List.of();
    }

    Set<UUID> categoryIds = new HashSet<>();
    for (BiBudgetAlert row : raised) {
      if (row.getCategoryId() != null) {
        categoryIds.add(row.getCategoryId());
      }
    }
    Map<UUID, BiCategory> byId = new HashMap<>();
    if (!categoryIds.isEmpty()) {
      for (BiCategory category : categories.findAllById(categoryIds)) {
        byId.put(category.getId(), category);
      }
    }

    List<BudgetAlertDto> out = new ArrayList<>(raised.size());
    for (BiBudgetAlert row : raised) {
      out.add(toDto(row, byId.get(row.getCategoryId())));
    }
    return out;
  }

  @Override
  @Transactional
  public BudgetAlertDto acknowledge(Caller caller, UUID alertId) {
    BiBudgetAlert alert =
        alerts
            .findByIdAndUserId(alertId, caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_FOUND));

    // Acknowledging twice is not an error, and the second one does not move the timestamp: when
    // the customer first saw it is the fact worth keeping.
    if (alert.getAcknowledgedAt() == null) {
      alert.setAcknowledgedAt(OffsetDateTime.now());
      alert = alerts.save(alert);
      log.info(
          "Insights alert acknowledged userId={} alertId={} type={}",
          caller.userId(),
          alertId,
          alert.getAlertType());
    } else {
      log.info(
          "Insights alert already acknowledged userId={} alertId={}", caller.userId(), alertId);
    }
    BiCategory category =
        alert.getCategoryId() == null
            ? null
            : categories.findById(alert.getCategoryId()).orElse(null);
    return toDto(alert, category);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private BiBudgetAlert alert(
      Caller caller,
      LocalDate periodStart,
      UUID categoryId,
      BudgetAlertType type,
      Short thresholdPct,
      BigDecimal amount,
      BigDecimal limit) {

    return BiBudgetAlert.builder()
        .userId(caller.userId())
        .tenantId(caller.tenantId())
        .periodStart(periodStart)
        .categoryId(categoryId)
        .alertType(type)
        .thresholdPct(thresholdPct)
        .amountAtRaise(amount)
        .limitAtRaise(limit)
        .raisedAt(OffsetDateTime.now())
        .build();
  }

  private BudgetAlertDto toDto(BiBudgetAlert row, BiCategory category) {
    return BudgetAlertDto.builder()
        .id(row.getId())
        .type(row.getAlertType().name())
        .periodStart(row.getPeriodStart())
        .categoryCode(category == null ? null : category.getCode())
        .categoryName(category == null ? null : category.getNameEn())
        .thresholdPct(row.getThresholdPct())
        .amount(mapper.money(row.getAmountAtRaise()))
        .limit(mapper.money(row.getLimitAtRaise()))
        .currency(properties.getCurrency())
        .raisedAt(row.getRaisedAt())
        .acknowledgedAt(row.getAcknowledgedAt())
        .build();
  }

  /** The dedup key, matching the two partial unique indexes on the table. */
  private String key(UUID categoryId, BudgetAlertType type) {
    return (categoryId == null ? "-" : categoryId.toString()) + "|" + type.name();
  }

  private BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
