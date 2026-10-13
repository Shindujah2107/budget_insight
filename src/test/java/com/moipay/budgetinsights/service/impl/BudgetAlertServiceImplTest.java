package com.moipay.budgetinsights.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiBudget;
import com.moipay.budgetinsights.domain.BiBudgetAlert;
import com.moipay.budgetinsights.domain.BiBudgetCategoryLimit;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BudgetAlertType;
import com.moipay.budgetinsights.dto.BudgetAlertDto;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.repository.BiBudgetAlertRepository;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiBudgetRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.InsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Threshold alerts (BI-25).
 *
 * <p>The rule that matters is "exactly one". Everything else here is arithmetic the summary already
 * did; the new claim is about memory, and memory is what silently goes wrong — an alert raised
 * twice is not an error anywhere, it is just a customer being told the same thing on every ingest
 * batch until they stop reading alerts at all.
 */
@ExtendWith(MockitoExtension.class)
class BudgetAlertServiceImplTest {

  @Mock private BiBudgetAlertRepository alerts;
  @Mock private BiBudgetRepository budgets;
  @Mock private BiBudgetCategoryLimitRepository limits;
  @Mock private BiTransactionRepository transactions;
  @Mock private BiCategoryRepository categories;

  private InsightsProperties properties;
  private BudgetAlertServiceImpl service;

  private static final UUID USER = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final Caller CALLER = new Caller(USER, TENANT);
  private static final UUID BUDGET = UUID.randomUUID();
  private static final UUID DINING = UUID.randomUUID();
  private static final LocalDate MID_MONTH = LocalDate.of(2026, 9, 17);
  private static final LocalDate MONTH_START = LocalDate.of(2026, 9, 1);

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    properties.setEnabled(true);
    properties.setCurrency("LKR");
    properties.setDefaultCategoryAlertThresholdPct((short) 80);
    properties.getAlerts().setEnabled(true);
    service =
        new BudgetAlertServiceImpl(
            properties,
            BudgetServiceImplTest.policyFrom(properties),
            new InsightsMapper(),
            alerts,
            budgets,
            limits,
            transactions,
            categories);
  }

  // ---------------------------------------------------------------------------------
  // The feature flag
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("With the flag off nothing is read and nothing is written")
  void flagOffDoesNothing() {
    properties.getAlerts().setEnabled(false);

    service.evaluate(CALLER, MID_MONTH);

    // Not merely "raises no alert" -- it must not touch the database at all, so that turning R6
    // off is a true rollback rather than a silent one that still costs queries on every write.
    verify(budgets, never()).findByUserIdAndPeriodStart(any(), any());
    verify(alerts, never()).saveAll(any());
  }

  @Test
  @DisplayName("A customer with no budget is not told anything about one")
  void noBudgetNoAlert() {
    when(budgets.findByUserIdAndPeriodStart(USER, MONTH_START)).thenReturn(Optional.empty());

    service.evaluate(CALLER, MID_MONTH);

    verify(alerts, never()).saveAll(any());
  }

  // ---------------------------------------------------------------------------------
  // Overall budget
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("The overall monthly target")
  class Overall {

    @Test
    @DisplayName("Reaching the target raises BUDGET_EXCEEDED, with category left null")
    void raisesWhenTargetReached() {
      stubBudget("30000.00");
      stubNoCaps();
      when(transactions.sumSpendBetween(eq(USER), eq(MONTH_START), any()))
          .thenReturn(new BigDecimal("30000.00"));

      service.evaluate(CALLER, MID_MONTH);

      BiBudgetAlert raised = onlyRaised();
      assertEquals(BudgetAlertType.BUDGET_EXCEEDED, raised.getAlertType());
      // Null category is what distinguishes an overall alert from a category one, and is why the
      // table needs two partial unique indexes rather than one constraint.
      assertNull(raised.getCategoryId());
      assertEquals(MONTH_START, raised.getPeriodStart());
      assertEquals(USER, raised.getUserId());
      assertEquals(TENANT, raised.getTenantId());
      assertEquals(0, new BigDecimal("30000.00").compareTo(raised.getAmountAtRaise()));
      assertEquals(0, new BigDecimal("30000.00").compareTo(raised.getLimitAtRaise()));
      // No threshold: it fired at the target itself, not at a share of it.
      assertNull(raised.getThresholdPct());
    }

    @Test
    @DisplayName("Spending under the target raises nothing")
    void silentUnderTarget() {
      stubBudget("30000.00");
      stubNoCaps();
      when(transactions.sumSpendBetween(eq(USER), eq(MONTH_START), any()))
          .thenReturn(new BigDecimal("29999.99"));

      service.evaluate(CALLER, MID_MONTH);

      verify(alerts, never()).saveAll(any());
    }

    @Test
    @DisplayName("Already raised this period, so not raised again")
    void notRaisedTwice() {
      stubBudget("30000.00");
      stubNoCaps();
      when(transactions.sumSpendBetween(eq(USER), eq(MONTH_START), any()))
          .thenReturn(new BigDecimal("45000.00"));
      when(alerts.findAllByUserIdAndPeriodStart(USER, MONTH_START))
          .thenReturn(List.of(existing(null, BudgetAlertType.BUDGET_EXCEEDED)));

      service.evaluate(CALLER, MID_MONTH);

      // The whole point. Spend keeps climbing all month; the customer is told once.
      verify(alerts, never()).saveAll(any());
    }
  }

  // ---------------------------------------------------------------------------------
  // Category caps
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("Per-category caps")
  class Categories {

    @Test
    @DisplayName("Crossing the configured share raises CATEGORY_APPROACHING with the threshold")
    void approaching() {
      stubBudget(null);
      stubCap(DINING, "1000.00", (short) 80);
      stubCategorySpend(DINING, "800.00");

      service.evaluate(CALLER, MID_MONTH);

      BiBudgetAlert raised = onlyRaised();
      assertEquals(BudgetAlertType.CATEGORY_APPROACHING, raised.getAlertType());
      assertEquals(DINING, raised.getCategoryId());
      assertEquals((short) 80, raised.getThresholdPct());
    }

    @Test
    @DisplayName("Reaching the cap raises CATEGORY_EXCEEDED and carries no threshold")
    void exceeded() {
      stubBudget(null);
      stubCap(DINING, "1000.00", (short) 80);
      stubCategorySpend(DINING, "1000.00");

      BiBudgetAlert raised = raiseAndCapture();

      // Spending exactly the cap is EXCEEDED, not APPROACHING: there is nothing left to approach.
      assertEquals(BudgetAlertType.CATEGORY_EXCEEDED, raised.getAlertType());
      assertNull(raised.getThresholdPct());
    }

    @Test
    @DisplayName("A cap with no threshold of its own uses the deployment default")
    void fallsBackToTheConfiguredDefault() {
      properties.setDefaultCategoryAlertThresholdPct((short) 50);
      stubBudget(null);
      stubCap(DINING, "1000.00", null);
      stubCategorySpend(DINING, "500.00");

      assertEquals((short) 50, raiseAndCapture().getThresholdPct());
    }

    @Test
    @DisplayName("APPROACHING already raised does not block the later EXCEEDED")
    void approachingDoesNotSuppressExceeded() {
      stubBudget(null);
      stubCap(DINING, "1000.00", (short) 80);
      stubCategorySpend(DINING, "1200.00");
      when(alerts.findAllByUserIdAndPeriodStart(USER, MONTH_START))
          .thenReturn(List.of(existing(DINING, BudgetAlertType.CATEGORY_APPROACHING)));

      // Two distinct things happened to the customer and they should hear about both.
      assertEquals(BudgetAlertType.CATEGORY_EXCEEDED, raiseAndCapture().getAlertType());
    }

    @Test
    @DisplayName("The same crossing on a later write is not raised again")
    void repeatedProcessingIsIdempotent() {
      stubBudget(null);
      stubCap(DINING, "1000.00", (short) 80);
      stubCategorySpend(DINING, "1500.00");
      when(alerts.findAllByUserIdAndPeriodStart(USER, MONTH_START))
          .thenReturn(List.of(existing(DINING, BudgetAlertType.CATEGORY_EXCEEDED)));

      service.evaluate(CALLER, MID_MONTH);

      verify(alerts, never()).saveAll(any());
    }

    @Test
    @DisplayName("A cap of zero is not a cap and raises nothing")
    void zeroCapIsIgnored() {
      stubBudget(null);
      stubCap(DINING, "0.00", (short) 80);
      stubCategorySpend(DINING, "500.00");

      service.evaluate(CALLER, MID_MONTH);

      // Otherwise every category with a placeholder cap fires on the first rupee.
      verify(alerts, never()).saveAll(any());
    }
  }

  // ---------------------------------------------------------------------------------
  // Transaction boundary and concurrency
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A unique-index collision is not swallowed: it reaches the caller's retry")
  void collisionPropagates() {
    stubBudget(null);
    stubCap(DINING, "1000.00", (short) 80);
    stubCategorySpend(DINING, "1200.00");
    when(alerts.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("ux_bi_alert_user_period_cat_type"));

    // Another writer raised the same alert between this one's pre-read and its insert. The session
    // is poisoned either way, so swallowing would only delay the failure to the caller's flush
    // with the cause thrown away. Ingest retries once and finds the alert on the second pass.
    assertThrows(
        DataIntegrityViolationException.class, () -> service.evaluate(CALLER, MID_MONTH));
  }

  @Test
  @DisplayName("Any other failure is swallowed: an alert must not fail a recorded transaction")
  void otherFailuresAreSwallowed() {
    when(budgets.findByUserIdAndPeriodStart(USER, MONTH_START))
        .thenThrow(new IllegalStateException("boom"));

    service.evaluate(CALLER, MID_MONTH);

    verify(alerts, never()).saveAll(any());
  }

  @Test
  @DisplayName("A null date is ignored rather than resolved to today")
  void nullDateIsIgnored() {
    service.evaluate(CALLER, null);
    verify(budgets, never()).findByUserIdAndPeriodStart(any(), any());
  }

  // ---------------------------------------------------------------------------------
  // Period boundaries
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("Period boundaries")
  class Periods {

    @Test
    @DisplayName("Any day of the month keys the alert on the first of that month")
    void anyDayKeysOnMonthStart() {
      for (LocalDate day :
          List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 17), LocalDate.of(2026, 9, 30))) {
        org.mockito.Mockito.clearInvocations(alerts, budgets, limits, transactions);
        stubBudget("30000.00");
        stubNoCaps();
        when(transactions.sumSpendBetween(eq(USER), eq(MONTH_START), any()))
            .thenReturn(new BigDecimal("40000.00"));

        service.evaluate(CALLER, day);

        // Not the transaction's own date: the cap is monthly, and an alert keyed on the day would
        // match no bi_budget row and would re-raise on every day of the month.
        assertEquals(MONTH_START, onlyRaised().getPeriodStart(), "for " + day);
      }
    }

    @Test
    @DisplayName("The same crossing in the next month is a new alert")
    void nextMonthRaisesAgain() {
      LocalDate october = LocalDate.of(2026, 10, 1);
      when(budgets.findByUserIdAndPeriodStart(USER, october))
          .thenReturn(Optional.of(budget("30000.00")));
      when(limits.findAllByBudgetId(BUDGET)).thenReturn(List.of());
      when(transactions.sumSpendBetween(eq(USER), eq(october), any()))
          .thenReturn(new BigDecimal("31000.00"));
      when(alerts.findAllByUserIdAndPeriodStart(USER, october)).thenReturn(List.of());

      service.evaluate(CALLER, LocalDate.of(2026, 10, 14));

      // A budget is per period. September's alert says nothing about October.
      assertEquals(october, onlyRaised().getPeriodStart());
    }
  }

  // ---------------------------------------------------------------------------------
  // Read and acknowledge
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("Reading and dismissing")
  class Reading {

    @Test
    @DisplayName("Alerts come back newest first with the category resolved")
    void listsForThePeriod() {
      BiBudgetAlert row = existing(DINING, BudgetAlertType.CATEGORY_EXCEEDED);
      when(alerts.findAllByUserIdAndPeriodStartOrderByRaisedAtDesc(USER, MONTH_START))
          .thenReturn(List.of(row));
      when(categories.findAllById(any()))
          .thenReturn(List.of(BiCategory.builder().id(DINING).code("DINING_OUT").nameEn("Dining Out").build()));

      List<BudgetAlertDto> out = service.forPeriod(USER, MID_MONTH);

      assertEquals(1, out.size());
      assertEquals("DINING_OUT", out.get(0).getCategoryCode());
      assertEquals("Dining Out", out.get(0).getCategoryName());
      assertEquals("LKR", out.get(0).getCurrency());
      assertEquals("1200.00", out.get(0).getAmount());
    }

    @Test
    @DisplayName("No alerts is an empty list, not an error")
    void emptyIsFine() {
      when(alerts.findAllByUserIdAndPeriodStartOrderByRaisedAtDesc(USER, MONTH_START))
          .thenReturn(List.of());
      assertTrue(service.forPeriod(USER, MID_MONTH).isEmpty());
    }

    @Test
    @DisplayName("Acknowledging sets the timestamp once and does not move it")
    void acknowledgeIsIdempotent() {
      BiBudgetAlert row = existing(DINING, BudgetAlertType.CATEGORY_EXCEEDED);
      when(alerts.findByIdAndUserId(row.getId(), USER)).thenReturn(Optional.of(row));
      when(alerts.save(any(BiBudgetAlert.class))).thenAnswer(i -> i.getArgument(0));
      when(categories.findById(DINING)).thenReturn(Optional.empty());

      BudgetAlertDto first = service.acknowledge(CALLER, row.getId());
      assertNotNull(first.getAcknowledgedAt());
      OffsetDateTime when = row.getAcknowledgedAt();

      BudgetAlertDto second = service.acknowledge(CALLER, row.getId());

      // When they first saw it is the fact worth keeping; a second dismissal is a no-op.
      assertEquals(when, row.getAcknowledgedAt());
      assertEquals(first.getAcknowledgedAt(), second.getAcknowledgedAt());
      verify(alerts, org.mockito.Mockito.times(1)).save(any(BiBudgetAlert.class));
    }

    @Test
    @DisplayName("Another customer's alert id is a 404, not a 403")
    void foreignAlertIsNotFound() {
      UUID id = UUID.randomUUID();
      when(alerts.findByIdAndUserId(id, USER)).thenReturn(Optional.empty());

      // Resolved by id AND owner, so the refusal does not confirm the row exists.
      assertThrows(BudgetInsightsException.class, () -> service.acknowledge(CALLER, id));
    }
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private BiBudget budget(String overall) {
    return BiBudget.builder()
        .id(BUDGET)
        .userId(USER)
        .tenantId(TENANT)
        .periodStart(MONTH_START)
        .overallLimit(overall == null ? null : new BigDecimal(overall))
        .build();
  }

  private void stubBudget(String overall) {
    when(budgets.findByUserIdAndPeriodStart(USER, MONTH_START))
        .thenReturn(Optional.of(budget(overall)));
    when(alerts.findAllByUserIdAndPeriodStart(USER, MONTH_START)).thenReturn(List.of());
  }

  private void stubNoCaps() {
    when(limits.findAllByBudgetId(BUDGET)).thenReturn(List.of());
  }

  private void stubCap(UUID categoryId, String limit, Short thresholdPct) {
    when(limits.findAllByBudgetId(BUDGET))
        .thenReturn(
            List.of(
                BiBudgetCategoryLimit.builder()
                    .id(UUID.randomUUID())
                    .budgetId(BUDGET)
                    .categoryId(categoryId)
                    .limitAmount(new BigDecimal(limit))
                    .alertThresholdPct(thresholdPct)
                    .build()));
  }

  private void stubCategorySpend(UUID categoryId, String amount) {
    List<Object[]> rows = new ArrayList<>();
    rows.add(new Object[] {categoryId, new BigDecimal(amount)});
    when(transactions.aggregateSpendByCategoryBetween(eq(USER), eq(MONTH_START), any()))
        .thenReturn(rows);
  }

  private BiBudgetAlert existing(UUID categoryId, BudgetAlertType type) {
    return BiBudgetAlert.builder()
        .id(UUID.randomUUID())
        .userId(USER)
        .tenantId(TENANT)
        .periodStart(MONTH_START)
        .categoryId(categoryId)
        .alertType(type)
        .amountAtRaise(new BigDecimal("1200.00"))
        .limitAtRaise(new BigDecimal("1000.00"))
        .raisedAt(OffsetDateTime.now())
        .build();
  }

  @SuppressWarnings("unchecked")
  private BiBudgetAlert onlyRaised() {
    ArgumentCaptor<List<BiBudgetAlert>> captor = ArgumentCaptor.forClass(List.class);
    verify(alerts).saveAll(captor.capture());
    assertEquals(1, captor.getValue().size(), "expected exactly one alert");
    return captor.getValue().get(0);
  }

  private BiBudgetAlert raiseAndCapture() {
    service.evaluate(CALLER, MID_MONTH);
    return onlyRaised();
  }
}
