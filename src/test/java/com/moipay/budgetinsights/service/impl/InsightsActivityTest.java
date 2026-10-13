package com.moipay.budgetinsights.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiAccount;
import com.moipay.budgetinsights.domain.InstrumentType;
import com.moipay.budgetinsights.dto.PaymentActivityResponse;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.InsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The payment activity chart (BI-16, R5).
 *
 * <p>Almost everything here is about the shape of the answer rather than its arithmetic. The
 * database returns only the days that have spend; the contract promises seven points per series,
 * two series, every time. A client that has to work out whether a missing day is a zero or a gap
 * will eventually work it out differently from the next client, and the two render differently.
 */
@ExtendWith(MockitoExtension.class)
class InsightsActivityTest {

  @Mock private BiTransactionRepository transactions;
  @Mock private BiCategoryRepository categories;
  @Mock private BiAccountRepository accounts;

  // BI-29 added read-through caching to the breakdown. This test exercises the activity chart,
  // which never touches it, so the collaborator is mocked rather than stood up: an unstubbed
  // Mockito mock answers Optional.empty() for a read and does nothing for a write, which is
  // exactly what an uninvolved cache should do.
  @Mock private InsightsAggregateCache cache;

  private InsightsQueryServiceImpl service;

  private static final UUID USER = UUID.randomUUID();
  private static final LocalDate MON = LocalDate.of(2026, 8, 10);
  private static final java.time.ZoneId COLOMBO = java.time.ZoneId.of("Asia/Colombo");

  @BeforeEach
  void setUp() {
    InsightsProperties properties = new InsightsProperties();
    properties.setEnabled(true);
    properties.setCurrency("LKR");
    properties.getActivity().setEnabled(true);
    service =
        new InsightsQueryServiceImpl(
            properties, new InsightsMapper(), transactions, categories, accounts, cache);
  }

  // ---------------------------------------------------------------------------------
  // Shape
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Two series of seven points, with zeros for the days that had no spend")
  void densifiesBothSeriesToSevenPoints() {
    when(transactions.aggregateDailySpendByInstrumentBetween(eq(USER), eq(MON), eq(MON.plusDays(6))))
        .thenReturn(
            rows(
                row(MON, InstrumentType.CARD, "1100.00"),
                row(MON.plusDays(3), InstrumentType.CARD, "3050.00"),
                row(MON.plusDays(3), InstrumentType.BANK, "2980.00")));

    PaymentActivityResponse response = service.activity(USER, MON, null);

    assertEquals(2, response.getSeries().size());
    for (PaymentActivityResponse.Series series : response.getSeries()) {
      assertEquals(7, series.getPoints().size(), series.getKey() + " must have seven points");
      // In order, one per day, no gaps to interpret.
      for (int i = 0; i < 7; i++) {
        assertEquals(MON.plusDays(i), series.getPoints().get(i).getDate());
      }
    }
    assertEquals("CARD", response.getSeries().get(0).getKey());
    assertEquals("BANK", response.getSeries().get(1).getKey());
    assertEquals("1100.00", amountOn(response, "CARD", MON));
    assertEquals("0.00", amountOn(response, "CARD", MON.plusDays(1)));
    assertEquals("0.00", amountOn(response, "BANK", MON));
    assertEquals("2980.00", amountOn(response, "BANK", MON.plusDays(3)));
  }

  @Test
  @DisplayName("maxValue spans both series, so the client never has to scan for the axis")
  void maxValueSpansBothSeries() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(
            rows(
                row(MON, InstrumentType.CARD, "1100.00"),
                row(MON.plusDays(1), InstrumentType.BANK, "4000.00")));

    assertEquals("4000.00", service.activity(USER, MON, null).getMaxValue());
  }

  @Test
  @DisplayName("A week with no spend at all is still two full series of zeros")
  void emptyWeekIsStillWellFormed() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(List.of());

    PaymentActivityResponse response = service.activity(USER, MON, null);

    // Not an empty payload and not a 404: a quiet week is a real answer, and the chart should
    // render a flat line rather than an error state.
    assertEquals(2, response.getSeries().size());
    assertEquals("0.00", response.getMaxValue());
    assertTrue(
        response.getSeries().stream()
            .allMatch(s -> s.getPoints().stream().allMatch(p -> "0.00".equals(p.getAmount()))));
    assertEquals(MON, response.getRange().getStart());
    assertEquals(MON.plusDays(6), response.getRange().getEnd());
    assertEquals(7, response.getRange().getDaysInPeriod());
  }

  @Test
  @DisplayName("No weekStart means the seven days ending today, in the configured zone")
  void defaultWindowEndsToday() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(List.of());

    // Bracketing the call rather than re-deriving "today" afterwards: a run that straddles local
    // midnight would otherwise compare against a date the service never saw.
    LocalDate before = LocalDate.now(COLOMBO);
    PaymentActivityResponse response = service.activity(USER, null, null);
    LocalDate after = LocalDate.now(COLOMBO);

    // A rolling window, not a calendar week -- the specification's own example starts on a
    // Wednesday, and this is the behaviour the R0 fixture published.
    LocalDate end = response.getRange().getEnd();
    assertTrue(
        end.equals(before) || end.equals(after),
        "the window should end today in the configured zone, got " + end);
    assertEquals(end.minusDays(6), response.getRange().getStart());
  }

  // ---------------------------------------------------------------------------------
  // The account filter
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Filtering to one account keeps both series; the other is seven zeros")
  void filterKeepsBothSeries() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, USER))
        .thenReturn(
            Optional.of(
                BiAccount.builder().id(accountId).userId(USER).isActive(Boolean.TRUE).build()));
    when(transactions.aggregateDailySpendByInstrumentForAccountBetween(
            eq(USER), eq(accountId), eq(MON), eq(MON.plusDays(6))))
        .thenReturn(rows(row(MON, InstrumentType.CARD, "500.00")));

    PaymentActivityResponse response = service.activity(USER, MON, accountId);

    // The point of the decision: the chart does not change shape when a filter is applied.
    assertEquals(2, response.getSeries().size());
    assertEquals(7, seriesOf(response, "BANK").getPoints().size());
    assertTrue(
        seriesOf(response, "BANK").getPoints().stream()
            .allMatch(p -> "0.00".equals(p.getAmount())));
    assertEquals("500.00", amountOn(response, "CARD", MON));
  }

  @Test
  @DisplayName("Another customer's account id is refused, not answered with an empty chart")
  void foreignAccountIsRefused() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, USER))
        .thenReturn(Optional.empty());

    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class, () -> service.activity(USER, MON, accountId));

    assertEquals(ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND, thrown.getErrorCode());
    // An empty chart is indistinguishable from a quiet week, so a caller probing ids would learn
    // nothing from the data -- and would also never be refused, which is the half that matters.
    verify(transactions, never()).aggregateDailySpendByInstrumentBetween(any(), any(), any());
    verify(transactions, never())
        .aggregateDailySpendByInstrumentForAccountBetween(any(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------------
  // Spend the chart cannot show
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Spend with no account is reported separately, not silently lost")
  void unattributedSpendIsReported() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(rows(row(MON, InstrumentType.BANK, "800.00")));
    when(transactions.sumUnattributedSpendBetween(eq(USER), eq(MON), eq(MON.plusDays(6))))
        .thenReturn(new BigDecimal("1250.00"));

    PaymentActivityResponse response = service.activity(USER, MON, null);

    // Cash, manual entries, and -- while masked-card-to-backend-enabled is off -- every ingested
    // card transaction. Without this the customer compares the chart against the breakdown and
    // finds money missing with nothing to explain it.
    assertEquals("1250.00", response.getUnattributedTotal());
    // It is not in either series, and it does not move the axis.
    assertEquals("800.00", response.getMaxValue());
  }

  @Test
  @DisplayName("With nothing unattributed the field is absent, not \"0.00\"")
  void unattributedIsOmittedWhenZero() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(List.of());

    assertNull(service.activity(USER, MON, null).getUnattributedTotal());
  }

  @Test
  @DisplayName("A null sum from the database is treated as nothing, not as a failure")
  void nullUnattributedSumIsSafe() {
    when(transactions.aggregateDailySpendByInstrumentBetween(any(), any(), any()))
        .thenReturn(List.of());
    when(transactions.sumUnattributedSpendBetween(any(), any(), any())).thenReturn(null);

    // SUM over no rows is null in SQL, which is the ordinary case for a customer who pays by card
    // for everything -- not an edge case worth a 500.
    assertNull(service.activity(USER, MON, null).getUnattributedTotal());
  }

  @Test
  @DisplayName("A week outside the approved look-back is refused, like every other read route")
  void lookbackIsEnforced() {
    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class,
            () -> service.activity(USER, LocalDate.now(COLOMBO).minusMonths(9), null));

    // The look-back is a Compliance position, not a validation nicety. /breakdown refuses the same
    // dates; /activity must not be the one endpoint that serves the ledger from outside it.
    assertEquals(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED, thrown.getErrorCode());
    verify(transactions, never()).aggregateDailySpendByInstrumentBetween(any(), any(), any());
    verify(transactions, never()).sumUnattributedSpendBetween(any(), any(), any());
  }

  @Test
  @DisplayName("An inactive account is refused, because the filter list never offered it")
  void inactiveAccountIsRefused() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, USER))
        .thenReturn(
            Optional.of(
                BiAccount.builder().id(accountId).userId(USER).isActive(Boolean.FALSE).build()));

    assertEquals(
        ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND,
        assertThrows(BudgetInsightsException.class, () -> service.activity(USER, MON, accountId))
            .getErrorCode());
  }

  @Test
  @DisplayName("Under an account filter the unattributed figure is withheld, not just unlabelled")
  void unattributedIsNotReportedUnderAFilter() {
    UUID accountId = UUID.randomUUID();
    when(accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, USER))
        .thenReturn(
            Optional.of(
                BiAccount.builder().id(accountId).userId(USER).isActive(Boolean.TRUE).build()));
    when(transactions.aggregateDailySpendByInstrumentForAccountBetween(
            eq(USER), eq(accountId), eq(MON), eq(MON.plusDays(6))))
        .thenReturn(rows(row(MON, InstrumentType.CARD, "500.00")));

    assertNull(service.activity(USER, MON, accountId).getUnattributedTotal());
    // Its justification is that the customer can reconcile the chart against the breakdown for the
    // same week. That does not hold once a filter is applied, and a true number answering a
    // question nobody asked reads as an error in the chart.
    verify(transactions, never()).sumUnattributedSpendBetween(any(), any(), any());
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private List<Object[]> rows(Object[]... rows) {
    return new ArrayList<>(List.of(rows));
  }

  private Object[] row(LocalDate date, InstrumentType instrument, String amount) {
    return new Object[] {date, instrument, new BigDecimal(amount)};
  }

  private PaymentActivityResponse.Series seriesOf(PaymentActivityResponse response, String key) {
    return response.getSeries().stream()
        .filter(s -> key.equals(s.getKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no series " + key));
  }

  private String amountOn(PaymentActivityResponse response, String key, LocalDate date) {
    return seriesOf(response, key).getPoints().stream()
        .filter(p -> date.equals(p.getDate()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no point " + date))
        .getAmount();
  }
}
