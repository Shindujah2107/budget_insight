package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiAccount;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.InstrumentType;
import com.moipay.budgetinsights.domain.BiTransaction;
import com.moipay.budgetinsights.dto.AccountDto;
import com.moipay.budgetinsights.dto.CategoryDto;
import com.moipay.budgetinsights.dto.CategoryTransactionsResponse;
import com.moipay.budgetinsights.dto.PaymentActivityResponse;
import com.moipay.budgetinsights.dto.PeriodDto;
import com.moipay.budgetinsights.dto.SpendingBreakdownResponse;
import com.moipay.budgetinsights.dto.TransactionDto;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.InsightsMapper;
import com.moipay.budgetinsights.service.InsightsQueryService;
import com.moipay.budgetinsights.service.calc.InsightsPeriod;
import com.moipay.budgetinsights.service.calc.ShareAllocator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsQueryServiceImpl implements InsightsQueryService {

  /** Seven points per series, per the contract published in R0. The chart draws exactly one week. */
  private static final int WEEK_DAYS = 7;

  /** Guards against a client asking for an unbounded page. */
  private static final int MAX_PAGE_SIZE = 100;

  private final InsightsProperties properties;
  private final InsightsMapper mapper;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiAccountRepository accounts;
  private final InsightsAggregateCache cache;

  // ---------------------------------------------------------------------------------
  // Breakdown
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public SpendingBreakdownResponse breakdown(UUID userId, LocalDate from, LocalDate to) {
    return breakdown(userId, period(from, to));
  }

  /**
   * The ranked spending breakdown for one period.
   *
   * <p>The full list comes back — at most seventeen rows — so the screen has no "see all" call to
   * make. Shares are apportioned by {@link ShareAllocator} rather than rounded independently, which
   * is what keeps the column adding up to 100.
   */
  @Override
  @Transactional(readOnly = true)
  public SpendingBreakdownResponse breakdown(UUID userId, InsightsPeriod period) {
    // BI-29. The invalidation half of this has worked since R1 and had nothing to invalidate:
    // every write to the ledger clears the overlapping rows in its own transaction, so a hit here
    // can only be a period nothing has touched since it was computed. Whole months only -- see
    // InsightsAggregateCache on why a date picker must not be able to fill the table.
    Optional<SpendingBreakdownResponse> cached = cache.read(userId, period);
    if (cached.isPresent()) {
      log.info(
          "Insights breakdown userId={} period={} categories={} source=cache",
          userId,
          period,
          cached.get().getCategoryCount());
      return cached.get();
    }
    SpendingBreakdownResponse computed = computeBreakdown(userId, period);
    cache.write(userId, period, computed);
    log.info(
        "Insights breakdown userId={} period={} categories={} source=computed",
        userId,
        period,
        computed.getCategoryCount());
    return computed;
  }

  private SpendingBreakdownResponse computeBreakdown(UUID userId, InsightsPeriod period) {
    List<Object[]> rows =
        transactions.aggregateSpendByCategoryBetween(userId, period.start(), period.end());

    List<UUID> ids = new ArrayList<>(rows.size());
    List<BigDecimal> amounts = new ArrayList<>(rows.size());
    List<Long> counts = new ArrayList<>(rows.size());
    BigDecimal total = BigDecimal.ZERO;

    for (Object[] row : rows) {
      UUID categoryId = (UUID) row[0];
      BigDecimal amount = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
      long count = row[2] == null ? 0L : ((Number) row[2]).longValue();
      ids.add(categoryId);
      amounts.add(amount);
      counts.add(count);
      total = total.add(amount);
    }

    Map<UUID, BiCategory> byId = loadCategories(ids);
    List<Double> shares = ShareAllocator.allocate(amounts);

    List<SpendingBreakdownResponse.CategorySpend> out = new ArrayList<>(rows.size());
    for (int i = 0; i < ids.size(); i++) {
      BiCategory category = byId.get(ids.get(i));
      long count = counts.get(i);
      out.add(
          SpendingBreakdownResponse.CategorySpend.builder()
              .categoryId(ids.get(i))
              .code(category == null ? null : category.getCode())
              .name(category == null ? null : category.getNameEn())
              .iconKey(category == null ? null : category.getIconKey())
              .amount(mapper.money(amounts.get(i)))
              .sharePct(shares.get(i))
              .transactionCount((int) count)
              .hasMore(count > 0)
              .build());
    }

    return SpendingBreakdownResponse.builder()
        .range(period.toDto())
        .currency(properties.getCurrency())
        .total(mapper.money(total))
        .categoryCount(out.size())
        .returnedCount(out.size())
        .categories(out)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // One expanded category
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public CategoryTransactionsResponse categoryTransactions(
      UUID userId, UUID categoryId, LocalDate from, LocalDate to, int page, int size) {

    InsightsPeriod period = period(from, to);
    BiCategory category = requireCategory(categoryId);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);

    Page<BiTransaction> found =
        transactions.pageCategorySpend(
            userId, categoryId, period.start(), period.end(), PageRequest.of(safePage, safeSize));

    BigDecimal total =
        transactions.sumSpendByCategoryBetween(userId, categoryId, period.start(), period.end());

    Map<UUID, BiAccount> accountsOnPage = loadAccounts(userId, found.getContent());
    List<TransactionDto> items =
        found.getContent().stream()
            .map(t -> mapper.toDto(t, accountsOnPage.get(t.getAccountId())))
            .collect(Collectors.toList());
    log.info(
        "Insights category transactions userId={} category={} period={} page={} returned={} total={}",
        userId,
        category.getCode(),
        period,
        safePage,
        items.size(),
        found.getTotalElements());

    return CategoryTransactionsResponse.builder()
        .categoryId(category.getId())
        .code(category.getCode())
        .name(category.getNameEn())
        .total(mapper.money(total))
        .page(
            CategoryTransactionsResponse.PageDto.builder()
                .number(found.getNumber())
                .size(found.getSize())
                .totalElements(found.getTotalElements())
                .totalPages(found.getTotalPages())
                .build())
        .transactions(items)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Reference lists
  // ---------------------------------------------------------------------------------

  // ---------------------------------------------------------------------------------
  // Payment activity (BI-16)
  // ---------------------------------------------------------------------------------

  /**
   * Daily spend for one week, as two series the chart can draw without interpreting anything.
   *
   * <p>The database returns only the (day, instrument) pairs that have spend. Everything else here
   * is densification: seven points per series, in order, zeros filled in. That work is done on the
   * server on purpose -- a client left to decide whether a missing day is a zero or a gap will
   * eventually decide differently from the next client.
   */
  @Override
  @Transactional(readOnly = true)
  public PaymentActivityResponse activity(UUID userId, LocalDate weekStart, UUID accountId) {

    LocalDate today = LocalDate.now(properties.zoneId());
    LocalDate start = weekStart != null ? weekStart : today.minusDays(WEEK_DAYS - 1L);
    LocalDate end = start.plusDays(WEEK_DAYS - 1L);

    // The same Compliance bound every other read route enforces, applied here by hand rather than
    // through period(): that method resolves an arbitrary from/to into a month, which would turn
    // this seven-day window into something else. Without it /activity would be the one endpoint
    // that serves ledger data from outside the approved look-back -- and silently, since the
    // refusal is what gets logged, not the acceptance.
    if (start.isBefore(today.minusMonths(properties.getLookbackMonths()).withDayOfMonth(1))) {
      log.info(
          "Insights activity refused: week starting {} is outside the {}-month look-back",
          start,
          properties.getLookbackMonths());
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED);
    }

    // Resolving the account before querying is what makes another customer's account id a 404
    // rather than an empty chart. An empty chart is indistinguishable from a quiet week, so a
    // caller probing ids would learn nothing from the data -- but would also never be refused,
    // and "never refused" is the half of that which shows up in an audit.
    if (accountId != null) {
      BiAccount account =
          accounts
              .findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
              .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND));
      // GET /accounts offers active accounts only, so accepting an inactive one here would make
      // the chart filterable by something the filter list never showed.
      if (!Boolean.TRUE.equals(account.getIsActive())) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND);
      }
    }

    Map<InstrumentType, Map<LocalDate, BigDecimal>> byInstrument =
        new EnumMap<>(InstrumentType.class);
    for (InstrumentType instrument : InstrumentType.values()) {
      byInstrument.put(instrument, new LinkedHashMap<>());
    }

    List<Object[]> rows =
        accountId == null
            ? transactions.aggregateDailySpendByInstrumentBetween(userId, start, end)
            : transactions.aggregateDailySpendByInstrumentForAccountBetween(
                userId, accountId, start, end);

    for (Object[] row : rows) {
      LocalDate date = (LocalDate) row[0];
      InstrumentType instrument = (InstrumentType) row[1];
      BigDecimal amount = (BigDecimal) row[2];
      if (date == null || instrument == null) {
        continue;
      }
      byInstrument.get(instrument).put(date, amount == null ? BigDecimal.ZERO : amount);
    }

    BigDecimal max = BigDecimal.ZERO;
    List<PaymentActivityResponse.Series> series = new ArrayList<>();
    for (InstrumentType instrument : InstrumentType.values()) {
      Map<LocalDate, BigDecimal> days = byInstrument.get(instrument);
      List<PaymentActivityResponse.Point> points = new ArrayList<>(WEEK_DAYS);
      for (int offset = 0; offset < WEEK_DAYS; offset++) {
        LocalDate date = start.plusDays(offset);
        BigDecimal amount = days.getOrDefault(date, BigDecimal.ZERO);
        max = max.max(amount);
        points.add(
            PaymentActivityResponse.Point.builder().date(date).amount(mapper.money(amount)).build());
      }
      // Both series, always -- including the one an account filter has emptied. A chart that
      // changes shape under a filter pushes the question of what an absent series means onto
      // every client that reads it.
      series.add(
          PaymentActivityResponse.Series.builder()
              .key(instrument.name())
              .label(seriesLabel(instrument))
              .points(points)
              .build());
    }

    // Only for the all-accounts chart. Its whole justification is that a customer can reconcile
    // the chart against the breakdown for the same week, and that reconciliation does not hold
    // once a filter is applied -- a cash total printed beside a single-card chart is a true number
    // answering a question nobody asked.
    BigDecimal unattributed =
        accountId == null ? transactions.sumUnattributedSpendBetween(userId, start, end) : null;
    boolean anyUnattributed = unattributed != null && unattributed.signum() > 0;

    log.info(
        "Insights activity userId={} start={} accountFiltered={} hasUnattributed={}",
        userId,
        start,
        accountId != null,
        anyUnattributed);

    return PaymentActivityResponse.builder()
        .range(PeriodDto.builder().start(start).end(end).daysInPeriod(WEEK_DAYS).build())
        .currency(properties.getCurrency())
        .maxValue(mapper.money(max))
        .series(series)
        // Omitted when there is none, so the field only appears when it has something to say.
        .unattributedTotal(anyUnattributed ? mapper.money(unattributed) : null)
        .build();
  }

  /**
   * The series label the chart's legend shows.
   *
   * <p>Fixed English, matching every other label this service returns that is not reference data.
   * Category names are localised because the translations live in {@code bi_category}; these two
   * have nowhere to be translated from, and inventing a localisation path for two strings would be
   * the wrong place to start one.
   */
  private String seriesLabel(InstrumentType instrument) {
    return InstrumentType.CARD.equals(instrument) ? "Card payments" : "Bank account payments";
  }

  @Override
  @Transactional(readOnly = true)
  public List<AccountDto> accounts(UUID userId) {
    List<AccountDto> out =
        accounts.findAllByUserIdAndDeletedAtIsNullAndIsActiveTrue(userId).stream()
            .map(mapper::toDto)
            .collect(Collectors.toList());
    log.info("Insights accounts userId={} count={}", userId, out.size());
    return out;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CategoryDto> categories() {
    List<CategoryDto> out =
        categories.findAllByIsActiveTrueOrderBySortOrderAsc().stream()
            .map(mapper::toDto)
            .collect(Collectors.toList());
    log.info("Insights categories listed count={}", out.size());
    return out;
  }

  // ---------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------

  /**
   * Resolves and validates the requested period.
   *
   * <p>The look-back bound is enforced here rather than left to the query, because a query that
   * simply returns nothing for an out-of-range date is indistinguishable from a customer with no
   * spending — and the look-back is a Compliance position, so exceeding it should be refused
   * visibly rather than absorbed.
   */
  @Override
  public InsightsPeriod period(LocalDate from, LocalDate to) {
    LocalDate today = LocalDate.now(properties.zoneId());
    InsightsPeriod period = InsightsPeriod.resolve(from, to, today);
    if (period.isInverted()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD);
    }
    // In the configured zone, not the container's UTC. Computed in UTC this boundary sits a
    // month early for the hours after local midnight on the 1st, which lets through a request
    // the approved look-back is meant to refuse -- a Compliance limit failing open.
    LocalDate earliest =
        today.minusMonths(properties.getLookbackMonths()).withDayOfMonth(1);
    if (period.startsBefore(earliest)) {
      // A Compliance boundary, not an ordinary validation failure. Six months is an approved
      // position, so an attempt to read beyond it is worth seeing in the log on its own. INFO
      // rather than ERROR: the request is refused correctly, nothing in the service has failed.
      log.info(
          "Insights request refused: period {} starts before the approved {}-month look-back "
              + "(earliest permitted {})",
          period,
          properties.getLookbackMonths(),
          earliest);
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED);
    }
    return period;
  }

  @Override
  public BiCategory requireCategory(UUID categoryId) {
    if (categoryId == null) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_CATEGORY_INVALID);
    }
    BiCategory category =
        categories
            .findById(categoryId)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_CATEGORY_INVALID));
    if (!Boolean.TRUE.equals(category.getIsActive())) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_CATEGORY_INVALID);
    }
    return category;
  }

  private Map<UUID, BiCategory> loadCategories(List<UUID> ids) {
    Map<UUID, BiCategory> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (BiCategory c : categories.findAllById(ids)) {
      byId.put(c.getId(), c);
    }
    return byId;
  }

  /**
   * Loads the accounts referenced by one page of transactions, in a single query.
   *
   * <p>Core walked a lazy {@code @ManyToOne} per row. Relations here are plain UUIDs, so this is
   * where that lookup happens — and doing it once for the page rather than once per row is the
   * reason the mapper takes the account as an argument.
   */
  private Map<UUID, BiAccount> loadAccounts(UUID userId, List<BiTransaction> page) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (BiTransaction t : page) {
      if (t.getAccountId() != null) {
        ids.add(t.getAccountId());
      }
    }
    Map<UUID, BiAccount> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (BiAccount a : accounts.findAllById(ids)) {
      // Scoped to the caller even here: findAllById cannot express ownership, so it is asserted
      // rather than assumed. A row that fails this is a defect, not a request to refuse.
      if (userId.equals(a.getUserId())) {
        byId.put(a.getId(), a);
      }
    }
    return byId;
  }
}
