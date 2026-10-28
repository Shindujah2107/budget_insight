package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.AuditAction;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.BiTransactionAudit;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.CreateTransactionRequest;
import com.moneyreserve.dto.SpendingBreakdownResponse;
import com.moneyreserve.dto.TransactionMutationResponse;
import com.moneyreserve.dto.UpdateTransactionRequest;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiTransactionAuditRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.BudgetAlertService;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import com.moneyreserve.service.InsightsMapper;
import com.moneyreserve.service.InsightsQueryService;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The three rules that fail silently.
 *
 * <p>The customer-choice latch, the idempotent retry, and cache invalidation on <b>both</b> dates
 * when a transaction moves month. None of them throws when broken; each produces a screen that is
 * quietly wrong, which is why they are worth a test apiece.
 */
@ExtendWith(MockitoExtension.class)
class InsightsTransactionServiceImplTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID TRANSPORT = UUID.randomUUID();
  private static final UUID WORK = UUID.randomUUID();
  private static final UUID TXN = UUID.randomUUID();
  /**
   * A date that is always in the past and always inside the look-back window, on every day the
   * suite might run. A fixed day-of-month would be a future date for the first part of each month
   * and would fail these tests on those days only.
   */
  private static final LocalDate SPEND_DATE = LocalDate.now().withDayOfMonth(1);

  private final Caller caller = new Caller(USER);

  @Mock private InsightsQueryService queries;
  @Mock private CategorisationService categorisation;
  @Mock private BudgetAlertService budgetAlerts;
  @Mock private BiTransactionRepository transactions;
  @Mock private BiTransactionAuditRepository audits;
  @Mock private BiAccountRepository accounts;
  @Mock private BiInsightCacheRepository caches;

  private InsightsTransactionServiceImpl service;

  private static BiCategory category(UUID id, String code) {
    return BiCategory.builder().id(id).code(code).nameEn(code).iconKey("ic").isActive(true).build();
  }

  @BeforeEach
  void setUp() {
    InsightsProperties properties = new InsightsProperties();
    properties.setLookbackMonths(6);
    properties.setCurrency("LKR");
    service =
        new InsightsTransactionServiceImpl(
            properties,
            new InsightsMapper(),
            queries,
            categorisation,
            budgetAlerts,
            transactions,
            audits,
            accounts,
            caches);

    lenient().when(transactions.save(any(BiTransaction.class))).thenAnswer(i -> i.getArgument(0));
    lenient().when(queries.period(any(), any()))
        .thenReturn(InsightsPeriod.ofMonth(YearMonth.from(SPEND_DATE)));
    lenient().when(queries.breakdown(eq(USER), any(InsightsPeriod.class)))
        .thenReturn(SpendingBreakdownResponse.builder().total("0.00").build());
  }

  private BiTransaction ingested(UUID categoryId, LocalDate date) {
    return BiTransaction.builder()
        .id(TXN).userId(USER)
        .amount(new BigDecimal("2450.00")).currency("LKR").direction(TxnDirection.DEBIT)
        .merchant("CEYPETCO FUEL").txnDate(date)
        .categoryId(categoryId).autoCategoryId(TRANSPORT)
        .isUserCategorised(Boolean.FALSE).source(TxnSource.SMS_ANDROID)
        .idempotencyKey("sha256:abc").build();
  }

  // ------------------------------------------------------------------ the latch

  @Test
  @DisplayName("correcting a category sets the latch and keeps what the engine chose")
  void recategoriseSetsLatchAndPreservesAutoCategory() {
    BiTransaction txn = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));
    when(queries.requireCategory(WORK)).thenReturn(category(WORK, "WORK"));

    TransactionMutationResponse res =
        service.update(caller, TXN, UpdateTransactionRequest.builder().categoryId(WORK).build(), null, null);

    assertEquals(WORK, txn.getCategoryId());
    assertEquals(TRANSPORT, txn.getAutoCategoryId(), "the engine's answer must survive the override");
    assertTrue(txn.getIsUserCategorised(), "the latch must be set");
    assertEquals(TRANSPORT, res.getPreviousCategoryId());
  }

  @Test
  @DisplayName("a re-categorisation writes an audit row carrying both category ids")
  void recategoriseIsAudited() {
    BiTransaction txn = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));
    when(queries.requireCategory(WORK)).thenReturn(category(WORK, "WORK"));

    service.update(caller, TXN, UpdateTransactionRequest.builder().categoryId(WORK).build(), null, null);

    ArgumentCaptor<BiTransactionAudit> cap = ArgumentCaptor.forClass(BiTransactionAudit.class);
    verify(audits).save(cap.capture());
    assertEquals(AuditAction.RECATEGORISE, cap.getValue().getAction());
    assertEquals(TRANSPORT, cap.getValue().getOldCategoryId());
    assertEquals(WORK, cap.getValue().getNewCategoryId());
    assertEquals("USER:" + USER, cap.getValue().getActor(), "an id, never a name or email");
  }

  @Test
  @DisplayName("setting the same category again changes nothing and writes no audit row")
  void noOpRecategorisation() {
    BiTransaction txn = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));

    service.update(caller, TXN, UpdateTransactionRequest.builder().categoryId(TRANSPORT).build(), null, null);

    verify(audits, never()).save(any());
    assertEquals(Boolean.FALSE, txn.getIsUserCategorised(), "an unchanged category must not latch");
  }

  // ------------------------------------------------- what may be edited, and on what

  @Test
  @DisplayName("editing an amount on an ingested row is refused")
  void amountEditRefusedOnIngestedRow() {
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER))
        .thenReturn(Optional.of(ingested(TRANSPORT, SPEND_DATE)));

    BudgetInsightsException ex =
        assertThrows(BudgetInsightsException.class, () ->
            service.update(caller, TXN,
                UpdateTransactionRequest.builder().amount(new BigDecimal("10.00")).build(), null, null));

    assertEquals(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE, ex.getErrorCode());
  }

  @Test
  @DisplayName("a transaction that is not the caller's is 404, not 403")
  void notFoundForAnotherOwner() {
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.empty());

    BudgetInsightsException ex =
        assertThrows(BudgetInsightsException.class, () ->
            service.delete(caller, TXN, null, null));

    assertEquals(ErrorCode.INSIGHTS_TXN_NOT_FOUND, ex.getErrorCode());
  }

  // ------------------------------------------------------------ cache invalidation

  @Test
  @DisplayName("a moved date invalidates the OLD month and the NEW month")
  void movedDateInvalidatesBothPeriods() {
    LocalDate oldDate = SPEND_DATE;
    LocalDate newDate = SPEND_DATE.minusMonths(1);
    BiTransaction txn = ingested(TRANSPORT, oldDate);
    txn.setSource(TxnSource.MANUAL);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));

    service.update(caller, TXN, UpdateTransactionRequest.builder().txnDate(newDate).build(), null, null);

    verify(caches).invalidateCovering(USER, oldDate);
    verify(caches).invalidateCovering(USER, newDate);
  }

  @Test
  @DisplayName("an unchanged date invalidates its one period once, not twice")
  void unchangedDateInvalidatesOnce() {
    BiTransaction txn = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));
    when(queries.requireCategory(WORK)).thenReturn(category(WORK, "WORK"));

    service.update(
        caller, TXN, UpdateTransactionRequest.builder().categoryId(WORK).build(), null, null);

    verify(caches, times(1)).invalidateCovering(USER, SPEND_DATE);
    verifyNoMoreInteractions(caches);
  }

  @Test
  @DisplayName("a delete invalidates the period it fell in")
  void deleteInvalidates() {
    BiTransaction txn = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByIdAndUserIdAndDeletedAtIsNull(TXN, USER)).thenReturn(Optional.of(txn));

    TransactionMutationResponse res = service.delete(caller, TXN, null, null);

    verify(caches).invalidateCovering(USER, SPEND_DATE);
    assertTrue(res.getDeleted());
    assertTrue(txn.isDeleted(), "soft delete, so the audit trail survives");
  }

  // ------------------------------------------------------------------ idempotency

  @Test
  @DisplayName("a repeated clientRef returns the existing row, flagged, and writes nothing")
  void retryReturnsTheOriginal() {
    BiTransaction existing = ingested(TRANSPORT, SPEND_DATE);
    when(transactions.findByUserIdAndIdempotencyKey(USER, "MANUAL:ref-1"))
        .thenReturn(Optional.of(existing));

    TransactionMutationResponse res =
        service.create(caller,
            CreateTransactionRequest.builder()
                .amount(new BigDecimal("2450.00")).txnDate(SPEND_DATE).clientRef("ref-1").build(),
            null, null);

    assertTrue(res.getDuplicate());
    assertEquals(TXN, res.getTransaction().getId());
    verify(transactions, never()).save(any());
    verify(audits, never()).save(any());
  }

  @Test
  @DisplayName("a manual add without a chosen category takes the engine's, unlatched")
  void manualAddUsesEngineCategory() {
    when(transactions.findByUserIdAndIdempotencyKey(eq(USER), any())).thenReturn(Optional.empty());
    when(categorisation.categorise(
            org.mockito.ArgumentMatchers.<CategorisationInput>argThat(
                i -> i != null && "CEYPETCO FUEL".equals(i.merchant()))))
        .thenReturn(category(TRANSPORT, "TRANSPORT"));

    service.create(caller,
        CreateTransactionRequest.builder()
            .amount(new BigDecimal("2450.00")).txnDate(SPEND_DATE).merchant("CEYPETCO FUEL").build(),
        null, null);

    ArgumentCaptor<BiTransaction> cap = ArgumentCaptor.forClass(BiTransaction.class);
    verify(transactions).save(cap.capture());
    assertEquals(TRANSPORT, cap.getValue().getCategoryId());
    assertEquals(TRANSPORT, cap.getValue().getAutoCategoryId());
    assertEquals(Boolean.FALSE, cap.getValue().getIsUserCategorised());
    assertEquals(TxnSource.MANUAL, cap.getValue().getSource());
    assertNull(cap.getValue().getAccountId(), "cash belongs to no instrument");
  }

  @Test
  @DisplayName("a future-dated entry is refused — that is a plan, not spending")
  void futureDateRefused() {
    BudgetInsightsException ex =
        assertThrows(BudgetInsightsException.class, () ->
            service.create(caller,
                CreateTransactionRequest.builder()
                    .amount(new BigDecimal("10.00")).txnDate(LocalDate.now().plusDays(1)).build(),
                null, null));

    assertEquals(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD, ex.getErrorCode());
  }

  @Test
  @DisplayName("a date before the approved look-back is refused")
  void beforeLookbackRefused() {
    BudgetInsightsException ex =
        assertThrows(BudgetInsightsException.class, () ->
            service.create(caller,
                CreateTransactionRequest.builder()
                    .amount(new BigDecimal("10.00")).txnDate(LocalDate.now().minusMonths(9)).build(),
                null, null));

    assertEquals(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED, ex.getErrorCode());
  }
}
