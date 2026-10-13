package com.moipay.budgetinsights.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiBudget;
import com.moipay.budgetinsights.domain.BiBudgetCategoryLimit;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.BudgetResponse;
import com.moipay.budgetinsights.dto.BudgetSummaryResponse;
import com.moipay.budgetinsights.dto.UpsertBudgetRequest;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiBudgetPolicyRepository;
import com.moipay.budgetinsights.repository.BiBudgetRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.BudgetService;
import com.moipay.budgetinsights.service.InsightsMapper;
import com.moipay.budgetinsights.service.InsightsQueryService;
import com.moipay.budgetinsights.service.calc.InsightsPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The budget rules that are wrong silently.
 *
 * <p>A missing target reported as a target of zero, a category cap that alerts one rupee too late,
 * caps left behind from the budget they belonged to, and a carry-forward that quietly loses them.
 * None of these throws; each just puts something wrong on the screen.
 *
 * <p>Every case runs against a month that has already finished, so the day arithmetic is fixed
 * whenever the suite runs. The arithmetic itself, including today's date landing inside the period,
 * is covered by {@code BudgetMathTest}.
 */
@ExtendWith(MockitoExtension.class)
class BudgetServiceImplTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID DINING_ID = UUID.randomUUID();

  /** A month that is over on every day the suite might run, so day counts never move. */
  private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

  private static final InsightsPeriod PERIOD = InsightsPeriod.ofMonth(MONTH);

  private final Caller caller = new Caller(USER, TENANT);

  @Mock private InsightsQueryService queries;
  @Mock private BiBudgetRepository budgets;
  @Mock private BiBudgetCategoryLimitRepository limits;
  @Mock private BiTransactionRepository transactions;
  @Mock private BiCategoryRepository categories;
  @Mock private ObjectProvider<BudgetService> self;

  private InsightsProperties properties;
  private BudgetServiceImpl service;


  /** The policy as configuration gives it, read on every call (no cache) so a test can change it. */
  static BudgetPolicyProvider policyFrom(InsightsProperties properties) {
    return new BudgetPolicyProvider(org.mockito.Mockito.mock(BiBudgetPolicyRepository.class), properties) {
      @Override
      public AdminBudgetPolicyState current() {
        return fallback();
      }
    };
  }

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    service =
        new BudgetServiceImpl(
            properties,
            policyFrom(properties),
            new InsightsMapper(),
            queries,
            budgets,
            limits,
            transactions,
            categories,
            self);
    // Stands in for the Spring proxy: in production this hop crosses a transaction boundary, and
    // in a unit test it is the same object.
    lenient().when(self.getObject()).thenReturn(service);
  }

  // ---------------------------------------------------------------------------------
  // Summary
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("with no target set, every budget-dependent figure is absent rather than zero")
  void noTargetOmitsRatherThanZeroes() {
    resolvesPeriod();
    spendInPeriod("4200.00");
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    noEarlierSpend();

    BudgetSummaryResponse out = service.summary(caller, MONTH);

    assertFalse(out.isBudgetSet());
    assertNull(out.getMonthlyTarget());
    assertNull(out.getRemaining());
    assertNull(out.getUtilisationPct());
    assertNull(out.getStatus());
    assertNull(out.getPace());
    // "5 days into your budget" would assert a budget the customer has not set.
    assertNull(out.getDaysText());
    // Spend is real whether or not a target exists, so it is still reported.
    assertEquals("4200.00", out.getSpentToDate());
  }

  @Test
  @DisplayName("the first-target suggestion is the median of the three months before")
  void suggestsTheMedianOfThreeMonths() {
    resolvesPeriod();
    spendInPeriod("0.00");
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    earlierSpend(1, "26000.00");
    earlierSpend(2, "450000.00");
    earlierSpend(3, "28000.00");

    BudgetSummaryResponse out = service.summary(caller, MONTH);

    // The middle month, not the mean -- one month with a wedding in it must not set the target.
    assertEquals("28000.00", out.getSuggestion().getSuggestedTarget());
    assertEquals("Median of your last three months", out.getSuggestion().getBasis());
  }

  @Test
  @DisplayName("a customer with no history is offered no suggestion rather than a target of zero")
  void noHistoryMeansNoSuggestion() {
    resolvesPeriod();
    spendInPeriod("0.00");
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    noEarlierSpend();

    assertNull(service.summary(caller, MONTH).getSuggestion());
  }

  @Test
  @DisplayName("a target under budget reports utilisation, remaining and ON_TRACK")
  void underBudget() {
    resolvesPeriod();
    spendInPeriod("15000.00");
    hasBudget("30000.00");
    when(limits.findAllByBudgetId(any())).thenReturn(List.of());

    BudgetSummaryResponse out = service.summary(caller, MONTH);

    assertTrue(out.isBudgetSet());
    assertEquals("30000.00", out.getMonthlyTarget());
    assertEquals("15000.00", out.getRemaining());
    assertEquals(50.0, out.getUtilisationPct());
    assertEquals("ON_TRACK", out.getStatus());
    assertEquals("UNDER", out.getPace().getVariance());
    assertEquals(PERIOD.days(), out.getDaysElapsed());
    assertEquals(0, out.getDaysRemaining());
    assertNull(out.getCategoryAlerts());
  }

  @Test
  @DisplayName("past the target, remaining goes negative and utilisation is not capped")
  void overBudgetIsNotClamped() {
    resolvesPeriod();
    spendInPeriod("45000.00");
    hasBudget("30000.00");
    when(limits.findAllByBudgetId(any())).thenReturn(List.of());

    BudgetSummaryResponse out = service.summary(caller, MONTH);

    assertEquals("-15000.00", out.getRemaining());
    assertEquals(150.0, out.getUtilisationPct());
    assertEquals("OVER_BUDGET", out.getStatus());
  }

  // ---------------------------------------------------------------------------------
  // Category alerts
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a category cap spent exactly to the limit is EXCEEDED, not APPROACHING")
  void capSpentExactlyIsExceeded() {
    assertEquals("EXCEEDED", alertStateFor("5000.00", "5000.00", (short) 80));
  }

  @Test
  @DisplayName("a category cap at its alert threshold is APPROACHING")
  void capAtThresholdIsApproaching() {
    assertEquals("APPROACHING", alertStateFor("4000.00", "5000.00", (short) 80));
  }

  @Test
  @DisplayName("a category cap below its threshold produces no alert at all")
  void capBelowThresholdIsSilent() {
    resolvesPeriod();
    spendInPeriod("3999.00");
    hasBudget("30000.00");
    hasCap("3999.00", "5000.00", (short) 80);

    assertNull(service.summary(caller, MONTH).getCategoryAlerts());
  }

  // ---------------------------------------------------------------------------------
  // Setting the target
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("caps are deleted and flushed before the replacements are inserted")
  void capsAreFlushedBeforeReinsert() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();
    when(categories.findByCodeAndIsActiveTrue("DINING_OUT")).thenReturn(Optional.of(dining()));
    when(limits.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
    when(categories.findAllById(any())).thenReturn(List.of(dining()));

    service.upsert(caller, upsertWith("30000.00", capRequest("DINING_OUT", "5000.00", null)));

    // Hibernate orders inserts ahead of deletes. Without the flush, re-saving a screen that still
    // has DINING_OUT on it collides with the DINING_OUT row about to be removed.
    InOrder order = inOrder(limits);
    order.verify(limits).deleteAllByBudgetId(any());
    order.verify(limits).flush();
    order.verify(limits).saveAll(anyList());
  }

  @Test
  @DisplayName("a saved budget carries the caller's tenant and the resolved period bounds")
  void savedBudgetCarriesTenantAndPeriod() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();

    service.upsert(caller, upsertWith("30000.00"));

    ArgumentCaptor<BiBudget> saved = ArgumentCaptor.forClass(BiBudget.class);
    verify(budgets).save(saved.capture());
    assertEquals(USER, saved.getValue().getUserId());
    assertEquals(TENANT, saved.getValue().getTenantId());
    assertEquals(PERIOD.start(), saved.getValue().getPeriodStart());
    assertEquals(PERIOD.end(), saved.getValue().getPeriodEnd());
    assertEquals(new BigDecimal("30000.00"), saved.getValue().getOverallLimit());
    assertNull(saved.getValue().getRolledFromId());
  }

  @Test
  @DisplayName("a carry-forward with no amount takes the previous target and its caps")
  void carryForwardCopiesTargetAndCaps() {
    resolvesPeriod();
    BiBudget previous = earlierBudget("24000.00");
    when(budgets.findFirstByUserIdAndPeriodStartLessThanOrderByPeriodStartDesc(
            USER, PERIOD.start()))
        .thenReturn(Optional.of(previous));
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();
    when(limits.findAllByBudgetId(previous.getId()))
        .thenReturn(List.of(cap(previous.getId(), "5000.00", (short) 75)));
    when(categories.findAllById(any())).thenReturn(List.of(dining()));
    when(categories.findByCodeAndIsActiveTrue("DINING_OUT")).thenReturn(Optional.of(dining()));
    when(limits.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

    BudgetResponse out =
        service.upsert(
            caller, UpsertBudgetRequest.builder().period(MONTH.toString()).rollForward(true).build());

    assertEquals("24000.00", out.getOverallLimit());
    assertTrue(out.getRolledForward());
    // The caps come with it. A budget the customer recognises with the alerts missing reads as a
    // bug, not as a carry-forward.
    assertEquals(1, out.getCategoryLimits().size());
    assertEquals("5000.00", out.getCategoryLimits().get(0).getLimitAmount());
    assertEquals(75, out.getCategoryLimits().get(0).getAlertThresholdPct());
  }

  @Test
  @DisplayName("a carry-forward with nothing to carry is refused, not silently ignored")
  void carryForwardWithNoPreviousIsRefused() {
    resolvesPeriod();
    when(budgets.findFirstByUserIdAndPeriodStartLessThanOrderByPeriodStartDesc(
            USER, PERIOD.start()))
        .thenReturn(Optional.empty());

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller,
                    UpsertBudgetRequest.builder()
                        .period(MONTH.toString())
                        .rollForward(true)
                        .build()));
    assertEquals(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID, ex.getErrorCode());
    verify(budgets, never()).save(any());
  }

  @Test
  @DisplayName("the same category twice is refused rather than one of them silently winning")
  void duplicateCategoryIsRefused() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();
    when(categories.findByCodeAndIsActiveTrue("DINING_OUT")).thenReturn(Optional.of(dining()));

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller,
                    upsertWith(
                        "30000.00",
                        capRequest("DINING_OUT", "5000.00", null),
                        capRequest("dining_out", "7000.00", null))));
    assertEquals(ErrorCode.INSIGHTS_CATEGORY_INVALID, ex.getErrorCode());
    verify(limits, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("a cap on a category that is not selectable is refused")
  void unknownCategoryIsRefused() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();
    when(categories.findByCodeAndIsActiveTrue("RETIRED")).thenReturn(Optional.empty());

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller, upsertWith("30000.00", capRequest("RETIRED", "1000.00", null))));
    assertEquals(ErrorCode.INSIGHTS_CATEGORY_INVALID, ex.getErrorCode());
  }

  @Test
  @DisplayName("an unparseable period is refused before anything is read or written")
  void badPeriodIsRefused() {
    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller,
                    UpsertBudgetRequest.builder()
                        .period("August")
                        .overallLimit(new BigDecimal("30000.00"))
                        .build()));
    assertEquals(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD, ex.getErrorCode());
    verify(budgets, never()).save(any());
  }

  @Test
  @DisplayName("a target of nothing at all is refused rather than stored as zero")
  void missingTargetIsRefused() {
    resolvesPeriod();

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller, UpsertBudgetRequest.builder().period(MONTH.toString()).build()));
    assertEquals(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID, ex.getErrorCode());
    verify(budgets, never()).save(any());
  }

  @Test
  @DisplayName("reading a period with no target returns the period, not a 404")
  void readingAnUnsetPeriodIsNotAnError() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());

    BudgetResponse out = service.get(caller, MONTH);

    assertFalse(out.isBudgetSet());
    assertEquals(PERIOD.start(), out.getPeriod().getStart());
    assertEquals("LKR", out.getCurrency());
    assertNull(out.getOverallLimit());
  }

  @Test
  @DisplayName("a carry-forward still finds the earlier period once this one already has a budget")
  void carryForwardLooksStrictlyBackwards() {
    resolvesPeriod();
    BiBudget previous = earlierBudget("24000.00");
    BiBudget existing = budget(PERIOD.start(), "30000.00");
    // The customer already saved this period, so their latest budget is this one. Asking for the
    // latest rather than the latest *earlier* one would refuse an ordinary re-save.
    when(budgets.findFirstByUserIdAndPeriodStartLessThanOrderByPeriodStartDesc(
            USER, PERIOD.start()))
        .thenReturn(Optional.of(previous));
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.of(existing));
    savesBudget();
    when(limits.findAllByBudgetId(previous.getId())).thenReturn(List.of());

    BudgetResponse out =
        service.upsert(
            caller,
            UpsertBudgetRequest.builder().period(MONTH.toString()).rollForward(true).build());

    assertEquals("24000.00", out.getOverallLimit());
    assertTrue(out.getRolledForward());
  }

  @Test
  @DisplayName("a target in a currency the deployment does not use is refused, not stored")
  void foreignCurrencyIsRefused() {
    resolvesPeriod();

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller,
                    UpsertBudgetRequest.builder()
                        .period(MONTH.toString())
                        .overallLimit(new BigDecimal("30000.00"))
                        .currency("USD")
                        .build()));
    assertEquals(ErrorCode.INSIGHTS_BUDGET_LIMIT_INVALID, ex.getErrorCode());
    // Storing a USD target against LKR spend would put a utilisation percentage on the screen
    // that divides one unit by another.
    verify(budgets, never()).save(any());
  }

  @Test
  @DisplayName("a null entry in the category limit list is refused rather than thrown over")
  void nullCategoryLimitEntryIsRefused() {
    resolvesPeriod();
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.empty());
    savesBudget();

    // @Valid cascades into the elements it is given, and a JSON null is an element, so this body
    // reaches the service. It should be the same 400 as any other bad cap, not a 500.
    List<UpsertBudgetRequest.CategoryLimit> withNull = new ArrayList<>();
    withNull.add(null);

    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.upsert(
                    caller,
                    UpsertBudgetRequest.builder()
                        .period(MONTH.toString())
                        .overallLimit(new BigDecimal("30000.00"))
                        .categoryLimits(withNull)
                        .build()));
    assertEquals(ErrorCode.INSIGHTS_CATEGORY_INVALID, ex.getErrorCode());
  }

  @Test
  @DisplayName("two saves racing for the same period retry once instead of answering 500")
  void concurrentSaveIsRetriedOnce() {
    BudgetService attempt = mock(BudgetService.class);
    when(self.getObject()).thenReturn(attempt);
    BudgetResponse saved = BudgetResponse.builder().budgetSet(true).build();
    // The loser of the race; by the time it runs again the winner has committed.
    when(attempt.upsertOnce(eq(caller), any()))
        .thenThrow(new DataIntegrityViolationException("ux_bi_budget_user_period"))
        .thenReturn(saved);

    assertSame(saved, service.upsert(caller, upsertWith("30000.00")));
    verify(attempt, times(2)).upsertOnce(eq(caller), any());
  }

  @Test
  @DisplayName("a violation that survives a clean retry is not swallowed")
  void persistentViolationIsNotSwallowed() {
    BudgetService attempt = mock(BudgetService.class);
    when(self.getObject()).thenReturn(attempt);
    when(attempt.upsertOnce(eq(caller), any()))
        .thenThrow(new DataIntegrityViolationException("something else entirely"));

    assertThrows(
        DataIntegrityViolationException.class,
        () -> service.upsert(caller, upsertWith("30000.00")));
    // Retried once and no further -- looping on a violation that is not a race just fails slower.
    verify(attempt, times(2)).upsertOnce(eq(caller), any());
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private String alertStateFor(String spent, String limit, short thresholdPct) {
    resolvesPeriod();
    spendInPeriod(spent);
    hasBudget("30000.00");
    hasCap(spent, limit, thresholdPct);

    List<BudgetSummaryResponse.CategoryAlert> alerts =
        service.summary(caller, MONTH).getCategoryAlerts();
    return alerts == null ? null : alerts.get(0).getState();
  }

  private void resolvesPeriod() {
    when(queries.period(PERIOD.start(), PERIOD.end())).thenReturn(PERIOD);
  }

  private void spendInPeriod(String amount) {
    when(transactions.sumSpendBetween(USER, PERIOD.start(), PERIOD.end()))
        .thenReturn(new BigDecimal(amount));
  }

  private void earlierSpend(int monthsBack, String amount) {
    YearMonth month = MONTH.minusMonths(monthsBack);
    when(transactions.sumSpendBetween(eq(USER), eq(month.atDay(1)), eq(month.atEndOfMonth())))
        .thenReturn(new BigDecimal(amount));
  }

  private void noEarlierSpend() {
    for (int back = 1; back <= 3; back++) {
      YearMonth month = MONTH.minusMonths(back);
      when(transactions.sumSpendBetween(eq(USER), eq(month.atDay(1)), eq(month.atEndOfMonth())))
          .thenReturn(null);
    }
  }

  private BiBudget hasBudget(String target) {
    BiBudget budget = budget(PERIOD.start(), target);
    when(budgets.findByUserIdAndPeriodStart(USER, PERIOD.start())).thenReturn(Optional.of(budget));
    return budget;
  }

  private void hasCap(String spent, String limit, short thresholdPct) {
    UUID budgetId = UUID.randomUUID();
    when(limits.findAllByBudgetId(any())).thenReturn(List.of(cap(budgetId, limit, thresholdPct)));
    // Built rather than List.of(...): a single Object[] handed to List.of spreads as varargs and
    // types as List<Object>, which is not what the repository returns.
    List<Object[]> aggregate = new ArrayList<>();
    aggregate.add(new Object[] {DINING_ID, new BigDecimal(spent), 3L});
    when(transactions.aggregateSpendByCategoryBetween(USER, PERIOD.start(), PERIOD.end()))
        .thenReturn(aggregate);
    when(categories.findAllById(any())).thenReturn(List.of(dining()));
  }

  private void savesBudget() {
    when(budgets.save(any(BiBudget.class)))
        .thenAnswer(
            invocation -> {
              BiBudget budget = invocation.getArgument(0);
              if (budget.getId() == null) {
                // Stands in for @PrePersist, which no mock will run.
                budget.setId(UUID.randomUUID());
              }
              return budget;
            });
  }

  private BiBudget earlierBudget(String target) {
    BiBudget budget = budget(PERIOD.start().minusMonths(1), target);
    budget.setId(UUID.randomUUID());
    return budget;
  }

  private BiBudget budget(LocalDate start, String target) {
    BiBudget budget = new BiBudget();
    budget.setId(UUID.randomUUID());
    budget.setUserId(USER);
    budget.setTenantId(TENANT);
    budget.setPeriodStart(start);
    budget.setPeriodEnd(YearMonth.from(start).atEndOfMonth());
    budget.setOverallLimit(new BigDecimal(target));
    budget.setCurrency("LKR");
    return budget;
  }

  private BiBudgetCategoryLimit cap(UUID budgetId, String limit, short thresholdPct) {
    BiBudgetCategoryLimit row = new BiBudgetCategoryLimit();
    row.setId(UUID.randomUUID());
    row.setBudgetId(budgetId);
    row.setCategoryId(DINING_ID);
    row.setLimitAmount(new BigDecimal(limit));
    row.setAlertThresholdPct(thresholdPct);
    return row;
  }

  private BiCategory dining() {
    BiCategory category = new BiCategory();
    category.setId(DINING_ID);
    category.setCode("DINING_OUT");
    category.setNameEn("Dining out");
    category.setIsActive(true);
    return category;
  }

  private UpsertBudgetRequest upsertWith(
      String target, UpsertBudgetRequest.CategoryLimit... caps) {
    return UpsertBudgetRequest.builder()
        .period(MONTH.toString())
        .overallLimit(new BigDecimal(target))
        .categoryLimits(caps.length == 0 ? null : List.of(caps))
        .build();
  }

  private UpsertBudgetRequest.CategoryLimit capRequest(
      String code, String amount, Integer thresholdPct) {
    return UpsertBudgetRequest.CategoryLimit.builder()
        .categoryCode(code)
        .limitAmount(new BigDecimal(amount))
        .alertThresholdPct(thresholdPct)
        .build();
  }
}
