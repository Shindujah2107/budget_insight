package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBudget;
import com.moneyreserve.domain.BiBudgetCategoryLimit;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.dto.BudgetResponse;
import com.moneyreserve.dto.BudgetSummaryResponse;
import com.moneyreserve.dto.UpsertBudgetRequest;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiBudgetCategoryLimitRepository;
import com.moneyreserve.repository.BiBudgetRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.BudgetService;
import com.moneyreserve.service.InsightsMapper;
import com.moneyreserve.service.InsightsQueryService;
import com.moneyreserve.service.calc.BudgetMath;
import com.moneyreserve.service.calc.CapState;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R2 — the monthly target and the summary that reports against it.
 *
 * <p>The period is resolved through {@link InsightsQueryService#period}, the same call the
 * breakdown uses, so the approved look-back is enforced in one place and a summary can never be
 * bounded differently from the breakdown sitting beneath it on the same screen.
 *
 * <p>The arithmetic lives in {@link BudgetMath}. What is left here is the part that needs a
 * database: which rows to read, what to do when there is no target, and how a target is replaced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetServiceImpl implements BudgetService {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  /** Kept for app builds that show {@code basis} as is; new builds use {@code periodMonths}. */
  static String basisText(int months) {
    return "Median of your last " + monthsWord(months) + " months";
  }

  static String monthsWord(int months) {
    String[] words = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve"};
    return months > 0 && months < words.length ? words[months] : String.valueOf(months);
  }


  private final InsightsProperties properties;
  private final BudgetPolicyProvider policy;
  private final InsightsMapper mapper;
  private final InsightsQueryService queries;
  private final BiBudgetRepository budgets;
  private final BiBudgetCategoryLimitRepository limits;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;

  /**
   * This bean through its own proxy, so the retry below starts a genuinely new transaction.
   *
   * <p>An {@link ObjectProvider} rather than a direct self-injection: it resolves on use, so the
   * bean does not have to be constructed while it is still being constructed.
   */
  private final ObjectProvider<BudgetService> self;

  // ---------------------------------------------------------------------------------
  // Summary
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public BudgetSummaryResponse summary(Caller caller, YearMonth month) {
    UUID userId = caller.userId();
    LocalDate today = today();
    InsightsPeriod period = resolve(month, today);

    BigDecimal spent = orZero(transactions.sumSpendBetween(userId, period.start(), period.end()));
    int elapsed = BudgetMath.daysElapsed(period, today);
    int remaining = BudgetMath.daysRemaining(period, today);

    Optional<BiBudget> found = budgets.findByUserIdAndPeriodStart(userId, period.start());

    BudgetSummaryResponse.BudgetSummaryResponseBuilder out =
        BudgetSummaryResponse.builder()
            .period(period.toDto())
            .spentToDate(mapper.money(spent))
            .daysElapsed(elapsed)
            .daysRemaining(remaining);

    if (found.isEmpty()) {
      log.info("Budget summary userId={} period={} budgetSet=false", userId, period);
      // No target. Every budget-dependent figure is omitted rather than sent as zero: a zero
      // target and an absent target are the same JSON otherwise, and they mean opposite things.
      // daysText goes with them -- "5 days into your budget" asserts a budget that is not there.
      return out.budgetSet(false)
          .currency(properties.getCurrency())
          .suggestion(suggest(userId, period))
          .build();
    }

    BiBudget budget = found.get();
    BigDecimal target = budget.getOverallLimit();
    BigDecimal utilisation = BudgetMath.utilisationPct(spent, target);
    BigDecimal averageDaily = BudgetMath.perDay(spent, elapsed);
    BigDecimal idealDaily = BudgetMath.perDay(target, period.days());
    String variance =
        BudgetMath.variance(averageDaily, idealDaily, policy.current().paceTolerancePct());
    log.info(
        "Budget summary userId={} period={} budgetSet=true status={} variance={}",
        userId,
        period,
        BudgetMath.status(utilisation, variance),
        variance);

    return out.budgetSet(true)
        .currency(currencyOf(budget))
        .monthlyTarget(mapper.money(target))
        // Negative on purpose once the target is passed. The screen needs to show how far over,
        // and a floor at zero would make every degree of overspend look identical.
        .remaining(mapper.money(target.subtract(spent)))
        .utilisationPct(utilisation.doubleValue())
        .status(BudgetMath.status(utilisation, variance))
        .daysText(BudgetMath.daysText(elapsed, remaining))
        .pace(
            BudgetSummaryResponse.Pace.builder()
                .averageDailySpend(mapper.money(averageDaily))
                .idealDailySpend(mapper.money(idealDaily))
                .variance(variance)
                .build())
        .categoryAlerts(alerts(userId, period, budget))
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Read and write the target
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public BudgetResponse get(Caller caller, YearMonth month) {
    InsightsPeriod period = resolve(month, today());
    Optional<BiBudget> found =
        budgets.findByUserIdAndPeriodStart(caller.userId(), period.start());

    log.info(
        "Budget read userId={} period={} budgetSet={}", caller.userId(), period, found.isPresent());
    if (found.isEmpty()) {
      return BudgetResponse.builder()
          .period(period.toDto())
          .budgetSet(false)
          .currency(properties.getCurrency())
          .defaultAlertThresholdPct(policy.current().defaultAlertPct())
          .build();
    }
    BiBudget budget = found.get();
    return toResponse(period, budget, limits.findAllByBudgetId(budget.getId()));
  }

  /**
   * Sets the target for a period, creating it or replacing what is there.
   *
   * <p>One transaction covers the target and its category caps together. A target saved without its
   * caps, or caps left behind after the target they belonged to was replaced, would drive alerts
   * against a limit the customer no longer has.
   */
  @Override
  public BudgetResponse upsert(Caller caller, UpsertBudgetRequest request) {
    try {
      return self.getObject().upsertOnce(caller, request);
    } catch (DataIntegrityViolationException ex) {
      // Two saves for the same period, in flight at once -- a double-tap on Save, or a client
      // retrying a request that was slower than its timeout. Both found no budget for the period
      // and both tried to insert one, and ux_bi_budget_user_period refused the loser.
      //
      // The constraint is doing its job; the 500 the customer saw was not. By the time we are
      // here the winner has committed, so the second attempt finds that row and updates it, which
      // is what the customer meant by tapping twice. One retry only: a violation that survives a
      // clean re-read is not a race, and looping on it would just fail more slowly.
      log.info(
          "Concurrent budget save for the same period userId={}; retrying once against the saved row",
          caller.userId());
      return self.getObject().upsertOnce(caller, request);
    }
  }

  @Override
  @Transactional
  public BudgetResponse upsertOnce(Caller caller, UpsertBudgetRequest request) {
    UUID userId = caller.userId();
    InsightsPeriod period = resolve(parseMonth(request.getPeriod()), today());
    boolean rollForward = Boolean.TRUE.equals(request.getRollForward());

    BiBudget previous = rollForward ? requirePrevious(userId, period) : null;

    BigDecimal target = request.getOverallLimit();
    if (target == null && previous != null) {
      target = previous.getOverallLimit();
    }
    if (target == null || target.signum() <= 0) {
      log.info("Budget save refused userId={} period={}: no positive overall limit", userId, period);
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID);
    }
    // Resolved before anything is loaded or mutated, so a refusal here never leaves a managed
    // entity half-rewritten in the persistence context.
    String currency = resolveCurrency(request);

    BiBudget budget =
        budgets.findByUserIdAndPeriodStart(userId, period.start()).orElseGet(BiBudget::new);
    budget.setUserId(userId);
    budget.setPeriodStart(period.start());
    budget.setPeriodEnd(period.end());
    budget.setOverallLimit(target.setScale(2, RoundingMode.HALF_UP));
    budget.setCurrency(currency);
    if (previous != null && budget.getRolledFromId() == null) {
      budget.setRolledFromId(previous.getId());
    }
    BiBudget saved = budgets.save(budget);

    List<UpsertBudgetRequest.CategoryLimit> requested = request.getCategoryLimits();
    if (requested == null && previous != null && request.getOverallLimit() == null) {
      // A pure carry-forward brings the caps with it. Carrying the target alone would leave the
      // customer with a budget they recognise and alerts they have lost, which reads as a bug.
      requested = copyOf(limits.findAllByBudgetId(previous.getId()));
    }

    List<BiBudgetCategoryLimit> stored = replaceCategoryLimits(saved, requested);

    log.info(
        "Budget saved userId={} period={} categoryLimits={} rolledForward={}",
        userId,
        period,
        stored.size(),
        saved.getRolledFromId() != null);

    return toResponse(period, saved, stored);
  }

  // ---------------------------------------------------------------------------------
  // Category alerts
  // ---------------------------------------------------------------------------------

  /**
   * The category caps that are worth saying something about.
   *
   * <p>Only caps in an alert state are returned. A category at a quarter of its limit is the normal
   * case and putting it on the screen next to one that has been blown would flatten the difference
   * between them.
   *
   * <p>Spend comes from one aggregate over the period, not a query per cap. A customer with caps on
   * eight categories would otherwise cost eight round trips to render one card.
   */
  private List<BudgetSummaryResponse.CategoryAlert> alerts(
      UUID userId, InsightsPeriod period, BiBudget budget) {

    List<BiBudgetCategoryLimit> configured = limits.findAllByBudgetId(budget.getId());
    if (configured.isEmpty()) {
      return null;
    }

    Map<UUID, BigDecimal> spendByCategory = new HashMap<>();
    for (Object[] row :
        transactions.aggregateSpendByCategoryBetween(userId, period.start(), period.end())) {
      spendByCategory.put((UUID) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
    }

    Map<UUID, BiCategory> byId = loadCategories(configured);

    List<BudgetSummaryResponse.CategoryAlert> out = new ArrayList<>();
    for (BiBudgetCategoryLimit cap : configured) {
      BigDecimal spent = spendByCategory.getOrDefault(cap.getCategoryId(), BigDecimal.ZERO);
      String state = alertState(spent, cap.getLimitAmount(), cap.getAlertThresholdPct());
      if (state == null) {
        continue;
      }
      BiCategory category = byId.get(cap.getCategoryId());
      out.add(
          BudgetSummaryResponse.CategoryAlert.builder()
              .categoryCode(category == null ? null : category.getCode())
              .limit(mapper.money(cap.getLimitAmount()))
              .spent(mapper.money(spent))
              .state(state)
              .build());
    }
    if (out.isEmpty()) {
      return null;
    }
    // Worst first, so a card that shows only the top few shows the ones that matter.
    out.sort(
        Comparator.comparingInt(
                (BudgetSummaryResponse.CategoryAlert a) -> "EXCEEDED".equals(a.getState()) ? 0 : 1)
            .thenComparing(
                (BudgetSummaryResponse.CategoryAlert a) -> new BigDecimal(a.getSpent()),
                Comparator.<BigDecimal>reverseOrder()));
    return out;
  }

  /**
   * Whether a cap is worth an alert, and which one, as the summary's string.
   *
   * <p>The rule itself is {@link BudgetMath#capState} — shared with the alert evaluation in R6, so
   * that what the card says and what the alert says cannot diverge.
   */
  private String alertState(BigDecimal spent, BigDecimal limit, Short thresholdPct) {
    CapState state =
        BudgetMath.capState(
            spent, limit, thresholdPct, (short) policy.current().defaultAlertPct());
    return state == null ? null : state.name();
  }

  // ---------------------------------------------------------------------------------
  // The no-target suggestion
  // ---------------------------------------------------------------------------------

  /**
   * A first target to offer a customer who has none.
   *
   * <p>The median of the three months before the one being viewed. Null when those months are all
   * empty: a suggested target of zero is not a suggestion, and a customer with no history is better
   * served by an empty field than by a number the service invented.
   *
   * <p>Returned unrounded. Rounding it to something tidy is a presentation decision, and the client
   * has the information to make it; doing it here would bake one market's idea of a round number
   * into the service.
   */
  private BudgetSummaryResponse.Suggestion suggest(UUID userId, InsightsPeriod period) {
    YearMonth viewed = YearMonth.from(period.start());
    int months = policy.current().trailingMonths();
    List<BigDecimal> monthly = new ArrayList<>(months);
    for (int back = 1; back <= months; back++) {
      YearMonth month = viewed.minusMonths(back);
      monthly.add(
          orZero(transactions.sumSpendBetween(userId, month.atDay(1), month.atEndOfMonth())));
    }
    BigDecimal median = BudgetMath.median(monthly);
    if (median.signum() <= 0) {
      return null;
    }
    return BudgetSummaryResponse.Suggestion.builder()
        .suggestedTarget(mapper.money(median))
        .basis(basisText(months))
        .periodMonths(months)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Writing the category caps
  // ---------------------------------------------------------------------------------

  /**
   * Replaces a period's category caps with the ones supplied.
   *
   * <p>Delete then insert, with a flush between. Hibernate orders inserts ahead of deletes in its
   * action queue, so re-saving a screen that still has DINING_OUT on it would collide with the
   * DINING_OUT row about to be removed — a unique-constraint violation on the most ordinary edit
   * there is. The flush is load-bearing, not tidiness.
   */
  private List<BiBudgetCategoryLimit> replaceCategoryLimits(
      BiBudget budget, List<UpsertBudgetRequest.CategoryLimit> requested) {

    limits.deleteAllByBudgetId(budget.getId());
    limits.flush();

    if (requested == null || requested.isEmpty()) {
      return List.of();
    }

    Set<String> seen = new LinkedHashSet<>();
    List<BiBudgetCategoryLimit> rows = new ArrayList<>(requested.size());

    for (UpsertBudgetRequest.CategoryLimit item : requested) {
      // A null element survives @Valid -- the cascade validates elements it is given, and a JSON
      // null is an element. Without this it is the one bad cap that answers 500 instead of 400.
      String code =
          item == null || item.getCategoryCode() == null
              ? null
              : item.getCategoryCode().trim().toUpperCase(Locale.ROOT);
      // A repeated code is refused rather than last-one-wins: the customer set two caps on one
      // category and only one can survive, so which one is a question only they can answer.
      if (code == null || code.isEmpty() || !seen.add(code)) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_CATEGORY_INVALID);
      }
      BiCategory category =
          categories
              .findByCodeAndIsActiveTrue(code)
              .orElseThrow(
                  () -> new BudgetInsightsException(ErrorCode.INSIGHTS_CATEGORY_INVALID));

      BigDecimal amount = item.getLimitAmount();
      if (amount == null || amount.signum() <= 0) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID);
      }

      // No value from the customer is stored as null: the cap then follows the default threshold
      // in the budget policy, including later changes to it. A value the customer chose is kept.
      Short threshold =
          item.getAlertThresholdPct() == null ? null : item.getAlertThresholdPct().shortValue();
      if (threshold != null && (threshold < 1 || threshold > 100)) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID);
      }

      BiBudgetCategoryLimit row = new BiBudgetCategoryLimit();
      row.setBudgetId(budget.getId());
      row.setCategoryId(category.getId());
      row.setLimitAmount(amount.setScale(2, RoundingMode.HALF_UP));
      row.setAlertThresholdPct(threshold);
      rows.add(row);
    }
    return limits.saveAll(rows);
  }

  /** The stored caps of an earlier period, in the shape a request would have sent them. */
  private List<UpsertBudgetRequest.CategoryLimit> copyOf(List<BiBudgetCategoryLimit> stored) {
    if (stored.isEmpty()) {
      return null;
    }
    Map<UUID, BiCategory> byId = loadCategories(stored);
    List<UpsertBudgetRequest.CategoryLimit> out = new ArrayList<>(stored.size());
    for (BiBudgetCategoryLimit cap : stored) {
      BiCategory category = byId.get(cap.getCategoryId());
      if (category == null || !Boolean.TRUE.equals(category.getIsActive())) {
        // A category retired between the two periods is dropped, not carried into a cap the
        // customer can no longer see or remove.
        log.info("Skipping carry-forward of a cap on a category that is no longer selectable");
        continue;
      }
      out.add(
          UpsertBudgetRequest.CategoryLimit.builder()
              .categoryCode(category.getCode())
              .limitAmount(cap.getLimitAmount())
              .alertThresholdPct(
                  cap.getAlertThresholdPct() == null ? null : (int) cap.getAlertThresholdPct())
              .build());
    }
    return out.isEmpty() ? null : out;
  }

  // ---------------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------------

  /**
   * Resolves a month to a period through the query service, so the look-back Compliance approved is
   * enforced by the same code that enforces it for the breakdown.
   */
  private InsightsPeriod resolve(YearMonth month, LocalDate today) {
    YearMonth resolved = month == null ? YearMonth.from(today) : month;
    return queries.period(resolved.atDay(1), resolved.atEndOfMonth());
  }

  private YearMonth parseMonth(String period) {
    if (period == null || period.isBlank()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD);
    }
    try {
      return YearMonth.parse(period.trim());
    } catch (DateTimeParseException ex) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD);
    }
  }

  private BiBudget requirePrevious(UUID userId, InsightsPeriod period) {
    return budgets
        .findFirstByUserIdAndPeriodStartLessThanOrderByPeriodStartDesc(userId, period.start())
        .orElseThrow(
            () ->
                new BudgetInsightsException(
                    ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID,
                    "There is no earlier budget to carry forward."));
  }

  /**
   * The currency a target is stored in, which is always the deployment's.
   *
   * <p>Budget Insights is single-currency by design, and a request naming a different one is
   * refused rather than honoured. Storing a target in one currency while every transaction counted
   * against it is in another would put a utilisation percentage on the screen that divides one unit
   * by another — a number that is wrong without looking wrong, which is the worst kind.
   */
  private String resolveCurrency(UpsertBudgetRequest request) {
    String configured = properties.getCurrency();
    if (request.getCurrency() != null && !request.getCurrency().isBlank()) {
      String requested = request.getCurrency().trim().toUpperCase(Locale.ROOT);
      if (!requested.equals(configured)) {
        throw new BudgetInsightsException(
            ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID,
            "Budget Insights works in " + configured + ".");
      }
    }
    return configured;
  }

  private String currencyOf(BiBudget budget) {
    return budget.getCurrency() == null ? properties.getCurrency() : budget.getCurrency();
  }

  private BudgetResponse toResponse(
      InsightsPeriod period, BiBudget budget, List<BiBudgetCategoryLimit> caps) {

    Map<UUID, BiCategory> byId = loadCategories(caps);
    int defaultAlert = policy.current().defaultAlertPct();
    List<BudgetResponse.CategoryLimitDto> items = new ArrayList<>(caps.size());
    for (BiBudgetCategoryLimit cap : caps) {
      BiCategory category = byId.get(cap.getCategoryId());
      items.add(
          BudgetResponse.CategoryLimitDto.builder()
              .categoryId(cap.getCategoryId())
              .categoryCode(category == null ? null : category.getCode())
              .categoryName(category == null ? null : category.getNameEn())
              .limitAmount(mapper.money(cap.getLimitAmount()))
              .alertThresholdPct(
                  cap.getAlertThresholdPct() == null
                      ? defaultAlert
                      : (int) cap.getAlertThresholdPct())
              .followsDefaultThreshold(cap.getAlertThresholdPct() == null)
              .build());
    }
    return BudgetResponse.builder()
        .period(period.toDto())
        .budgetSet(true)
        .currency(currencyOf(budget))
        .overallLimit(mapper.money(budget.getOverallLimit()))
        .rolledForward(budget.getRolledFromId() != null)
        .defaultAlertThresholdPct(defaultAlert)
        .categoryLimits(items.isEmpty() ? null : items)
        .build();
  }

  private Map<UUID, BiCategory> loadCategories(List<BiBudgetCategoryLimit> caps) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (BiBudgetCategoryLimit cap : caps) {
      if (cap.getCategoryId() != null) {
        ids.add(cap.getCategoryId());
      }
    }
    Map<UUID, BiCategory> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    for (BiCategory category : categories.findAllById(ids)) {
      byId.put(category.getId(), category);
    }
    return byId;
  }

  /** Today in the configured zone. The container's clock is UTC; the customer's day is not. */
  private LocalDate today() {
    return LocalDate.now(properties.zoneId());
  }

  private static BigDecimal orZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
