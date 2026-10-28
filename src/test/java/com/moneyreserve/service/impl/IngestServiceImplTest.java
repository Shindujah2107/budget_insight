package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiDeviceSyncState;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.ComplianceEventSource;
import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.IngestBatchRequest;
import com.moneyreserve.dto.IngestBatchResponse;
import com.moneyreserve.dto.IngestMessage;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiDeviceSyncStateRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.BudgetAlertService;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import com.moneyreserve.service.ConsentService;
import com.moneyreserve.service.InsightsComplianceService;
import com.moneyreserve.service.ingest.IngestRateLimiter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The ingest pipeline's rules (R4).
 *
 * <p>The tests that matter most here are the ones about what is <em>not</em> written. A guard that
 * runs but writes anyway, an OTP rejection that still leaves a row, a duplicate that becomes a
 * second grocery run on the customer's screen — each of those is a passing request and a wrong
 * answer, which is the only kind of defect this feature can produce without anybody noticing.
 */
@ExtendWith(MockitoExtension.class)
class IngestServiceImplTest {

  @Mock private ConsentService consent;
  @Mock private InsightsComplianceService compliance;
  @Mock private CategorisationService categorisation;
  @Mock private BudgetAlertService budgetAlerts;
  @Mock private BiTransactionRepository transactions;
  @Mock private BiAccountRepository accounts;
  @Mock private BiInsightCacheRepository caches;
  @Mock private BiDeviceSyncStateRepository syncStates;
  @Mock private BiBankParserTemplateRepository templates;
  @Mock private ObjectProvider<com.moneyreserve.service.IngestService> self;

  private InsightsProperties properties;
  private IngestServiceImpl service;

  private static final UUID USER = UUID.randomUUID();
  private static final Caller CALLER = new Caller(USER);
  private static final UUID GROCERIES = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    properties.setEnabled(true);
    properties.setCurrency("LKR");
    properties.setLookbackMonths(6);
    properties.getIngest().setEnabled(true);
    service = build(new IngestRateLimiter(5000, Duration.ofSeconds(60)));
  }

  private IngestServiceImpl build(IngestRateLimiter limiter) {
    return new IngestServiceImpl(
        properties,
        consent,
        compliance,
        categorisation,
        budgetAlerts,
        limiter,
        transactions,
        accounts,
        caches,
        syncStates,
        templates,
        self);
  }

  // ---------------------------------------------------------------------------------
  // The happy path
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A clean message becomes a transaction, namespaced away from manual entries")
  void acceptsACleanMessage() {
    stubHappyPath();

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message()));

    assertEquals(1, response.getAccepted());
    assertEquals(0, response.getDuplicates());
    assertEquals(0, response.getRejected());

    BiTransaction saved = captureSaved();
    assertEquals(USER, saved.getUserId());
    assertEquals(TxnSource.SMS_ANDROID, saved.getSource());
    // The namespace is what stops a hand-typed client reference from silently suppressing a bank
    // message that happened to produce the same string.
    assertEquals("SMS:sha256:aaaabbbbccccddddeeeeffff0000", saved.getIdempotencyKey());
    assertEquals(GROCERIES, saved.getCategoryId());
    // Both columns, always: auto_category_id is what the merchant map is judged on later.
    assertEquals(GROCERIES, saved.getAutoCategoryId());
    assertEquals(Boolean.FALSE, saved.getIsUserCategorised());

    // The device's own key comes back untouched, because that is the key in its outbox.
    assertEquals("sha256:aaaabbbbccccddddeeeeffff0000", response.getResults().get(0).getIdempotencyKey());
    assertEquals("ACCEPTED", response.getResults().get(0).getStatus());
  }

  // ---------------------------------------------------------------------------------
  // The OTP re-check
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("An OTP in the merchant field is rejected, recorded, and never written")
  void otpInMerchantIsRejectedAndRecorded() {
    // No stub for the duplicate pre-read here. Every message in this batch is refused in pass
    // one, so nothing reaches pass two and existingByKey short-circuits without touching the
    // repository -- stubbing it would be an unnecessary stubbing under STRICT_STUBS.

    IngestMessage leaked = message();
    leaked.setMerchant("Your OTP for online banking is 839201.");

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(leaked));

    assertEquals(0, response.getAccepted());
    assertEquals(1, response.getRejected());
    assertEquals(
        ErrorCode.INSIGHTS_OTP_PAYLOAD_REJECTED.getCode(),
        response.getResults().get(0).getError());

    // The incident is recorded even though the message is refused -- in its own transaction, so it
    // survives the refusal.
    verify(compliance)
        .raiseOtpDetected(eq(ComplianceEventSource.SERVER), anyString());
    verify(transactions, never()).save(any());
    verify(accounts, never()).save(any());
  }

  @Test
  @DisplayName("One poisoned message does not cost the customer the rest of the batch")
  void oneBadMessageDoesNotFailTheBatch() {
    stubHappyPath();

    IngestMessage leaked = message();
    leaked.setIdempotencyKey("sha256:1111111111111111111111111111");
    leaked.setMerchant("Do not share your PIN with anyone");

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message(), leaked));

    assertEquals(1, response.getAccepted());
    assertEquals(1, response.getRejected());
    assertEquals(2, response.getResults().size());
    verify(transactions, times(1)).save(any());
  }

  // ---------------------------------------------------------------------------------
  // Idempotency
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A replayed message is a duplicate, not a second grocery run")
  void replayIsReportedAsADuplicate() {
    UUID existingId = UUID.randomUUID();
    BiTransaction existing =
        BiTransaction.builder()
            .id(existingId)
            .idempotencyKey("SMS:sha256:aaaabbbbccccddddeeeeffff0000")
            .build();
    when(transactions.findAllByUserIdAndIdempotencyKeyIn(eq(USER), any()))
        .thenReturn(List.of(existing));
    stubDeviceState();

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message()));

    assertEquals(0, response.getAccepted());
    assertEquals(1, response.getDuplicates());
    // The device is told which transaction it already has, so it can clear its queue with
    // confidence rather than retrying forever.
    assertEquals(existingId, response.getResults().get(0).getTransactionId());
    verify(transactions, never()).save(any());
  }

  @Test
  @DisplayName("The same key twice inside one batch is caught before it reaches the database")
  void duplicateWithinOneBatch() {
    stubHappyPath();

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message(), message()));

    assertEquals(1, response.getAccepted());
    assertEquals(1, response.getDuplicates());
    // If this reached the insert it would violate ux_bi_txn_user_idem at flush, poisoning the
    // Hibernate session and taking the whole batch down with it.
    verify(transactions, times(1)).save(any());
    // The duplicate still names the transaction the device's message is represented by. Without
    // it the device cannot reconcile the second entry and will keep resending it.
    assertNotNull(response.getResults().get(1).getTransactionId());
    assertEquals(
        response.getResults().get(0).getTransactionId(),
        response.getResults().get(1).getTransactionId());
  }

  // ---------------------------------------------------------------------------------
  // Parser template identifiers
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A parser template id this deployment does not have is dropped, not fatal")
  void unknownParserTemplateIdIsDropped() {
    stubHappyPath();
    when(templates.findAllById(any())).thenReturn(List.of());

    IngestMessage message = message();
    message.setParserTemplateId(UUID.randomUUID());

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message));

    // bi_transaction.parser_template_id is a foreign key, and bi_bank_parser_template is unseeded
    // until the labelled sample set arrives. Writing an unknown id would raise a
    // DataIntegrityViolationException at flush -- which is not a BudgetInsightsException, so it
    // escapes the per-message catch, rolls back the whole batch, and fails identically on the
    // retry. The id is metadata for measuring parser accuracy; it is not worth forty good
    // messages.
    assertEquals(1, response.getAccepted());
    assertNull(captureSaved().getParserTemplateId());
  }

  @Test
  @DisplayName("A parser template id that exists is kept, because accuracy is measured by it")
  void knownParserTemplateIdSurvives() {
    stubHappyPath();
    UUID templateId = UUID.randomUUID();
    when(templates.findAllById(any()))
        .thenReturn(List.of(BiBankParserTemplate.builder().id(templateId).build()));

    IngestMessage message = message();
    message.setParserTemplateId(templateId);

    service.ingestOnce(CALLER, batch(message));

    assertEquals(templateId, captureSaved().getParserTemplateId());
  }

  // ---------------------------------------------------------------------------------
  // Normalisation
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A date beyond the look-back is refused with the code the device acts on")
  void lookbackIsEnforced() {
    // No stub for the duplicate pre-read here. Every message in this batch is refused in pass
    // one, so nothing reaches pass two and existingByKey short-circuits without touching the
    // repository -- stubbing it would be an unnecessary stubbing under STRICT_STUBS.

    IngestMessage old = message();
    old.setTxnDate(LocalDate.now().minusMonths(9));

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(old));

    assertEquals(1, response.getRejected());
    assertEquals(
        ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED.getCode(), response.getResults().get(0).getError());
    verify(transactions, never()).save(any());
  }

  @Test
  @DisplayName("A foreign currency is refused rather than converted at an unstated rate")
  void foreignCurrencyIsRefused() {
    // No stub for the duplicate pre-read here. Every message in this batch is refused in pass
    // one, so nothing reaches pass two and existingByKey short-circuits without touching the
    // repository -- stubbing it would be an unnecessary stubbing under STRICT_STUBS.

    IngestMessage usd = message();
    usd.setCurrency("USD");

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(usd));

    assertEquals(1, response.getRejected());
    assertEquals(
        ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID.getCode(),
        response.getResults().get(0).getError());
  }

  @Test
  @DisplayName("A tail longer than four digits is refused, never shortened")
  void unmaskedTailIsRefused() {
    // No stub for the duplicate pre-read here. Every message in this batch is refused in pass
    // one, so nothing reaches pass two and existingByKey short-circuits without touching the
    // repository -- stubbing it would be an unnecessary stubbing under STRICT_STUBS.

    IngestMessage wide = message();
    wide.setMaskedTail("482100");

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(wide));

    // Truncating would have made an unmasked instrument number look compliant.
    assertEquals(1, response.getRejected());
    verify(transactions, never()).save(any());
  }

  @Test
  @DisplayName("A message type the deployment has not opted into is refused")
  void outOfScopeMessageTypeIsRefused() {
    properties.getIngest().setAcceptedMessageTypes(List.of("CARD_PURCHASE"));
    // No stub for the duplicate pre-read here. Every message in this batch is refused in pass
    // one, so nothing reaches pass two and existingByKey short-circuits without touching the
    // repository -- stubbing it would be an unnecessary stubbing under STRICT_STUBS.

    IngestMessage balance = message();
    balance.setMessageType("ACCOUNT_BALANCE");

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(balance));

    // Whether balance messages are in scope is an open Compliance decision (D-3). Until it is
    // answered, a device cannot widen what this service stores by starting to send them.
    assertEquals(1, response.getRejected());
    verify(transactions, never()).save(any());
  }

  // ---------------------------------------------------------------------------------
  // Account resolution and the masked-card switch
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("With the card flag off the spend is still recorded, against no account")
  void cardTailIsDroppedWhileTheFlagIsOff() {
    stubHappyPath();
    properties.setMaskedCardToBackendEnabled(false);

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(message()));

    assertEquals(1, response.getAccepted());
    // The money moved; it belongs in the total. What is held back is the instrument it moved on.
    assertNull(captureSaved().getAccountId());
    verify(accounts, never()).save(any());
  }

  @Test
  @DisplayName("With the card flag on the account is resolved from the tail")
  void cardAccountIsResolvedWhenApproved() {
    stubHappyPath();
    properties.setMaskedCardToBackendEnabled(true);
    when(accounts.findByUserIdAndBankCodeAndInstrumentTypeAndMaskedTailAndDeletedAtIsNull(
            eq(USER), eq("COMB"), eq(InstrumentType.CARD), eq("4821")))
        .thenReturn(Optional.empty());
    when(accounts.save(any(BiAccount.class)))
        .thenAnswer(
            invocation -> {
              BiAccount account = invocation.getArgument(0);
              account.setId(UUID.randomUUID());
              return account;
            });

    service.ingestOnce(CALLER, batch(message()));

    ArgumentCaptor<BiAccount> captor = ArgumentCaptor.forClass(BiAccount.class);
    verify(accounts).save(captor.capture());
    assertEquals("4821", captor.getValue().getMaskedTail());
    assertNotNull(captureSaved().getAccountId());
  }

  @Test
  @DisplayName("A bank message with no tail is recorded, unattributed, with its own direction")
  void bankMessageWithoutTailIsStoredUnattributed() {
    // The V1.3.0 seed has five templates whose SMS shows fewer than four account digits (BOC,
    // People's Bank and HNB account alerts). The device sends those with no maskedTail at all.
    // The money still counts; it simply belongs to no account, and no account is invented for it.
    stubHappyPath();
    IngestMessage bank = message();
    bank.setBankCode("7010");
    bank.setMessageType("ACCOUNT_TXN");
    bank.setInstrumentType("BANK");
    bank.setDirection("CREDIT");
    bank.setMaskedTail(null);

    IngestBatchResponse response = service.ingestOnce(CALLER, batch(bank));

    assertEquals(1, response.getAccepted());
    BiTransaction saved = captureSaved();
    assertNull(saved.getAccountId());
    assertEquals("CREDIT", String.valueOf(saved.getDirection()));
    org.mockito.Mockito.verifyNoInteractions(accounts);
  }

  @Test
  @DisplayName("Two messages on the same new account create it once")
  void accountIsCreatedOncePerBatch() {
    stubHappyPath();
    properties.setMaskedCardToBackendEnabled(true);
    when(accounts.findByUserIdAndBankCodeAndInstrumentTypeAndMaskedTailAndDeletedAtIsNull(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty());
    when(accounts.save(any(BiAccount.class)))
        .thenAnswer(
            invocation -> {
              BiAccount account = invocation.getArgument(0);
              account.setId(UUID.randomUUID());
              return account;
            });

    IngestMessage second = message();
    second.setIdempotencyKey("sha256:2222222222222222222222222222");

    service.ingestOnce(CALLER, batch(message(), second));

    // Without the in-batch cache this is a second insert on ux_bi_account_user_bank_tail.
    verify(accounts, times(1)).save(any(BiAccount.class));
  }

  // ---------------------------------------------------------------------------------
  // Batch-level refusals
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Without consent nothing is read and nothing is written")
  void consentGatesTheWholeBatch() {
    org.mockito.Mockito.doThrow(new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_REQUIRED))
        .when(consent)
        .requireActiveConsent(USER);

    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class, () -> service.ingestOnce(CALLER, batch(message())));

    assertEquals(ErrorCode.INSIGHTS_CONSENT_REQUIRED, thrown.getErrorCode());
    verify(transactions, never()).save(any());
    verify(transactions, never()).findAllByUserIdAndIdempotencyKeyIn(any(), any());
  }

  @Test
  @DisplayName("A device over its rate limit is refused and recorded, with a retry hint")
  void rateLimitRefusesTheBatch() {
    service = build(new IngestRateLimiter(1, Duration.ofSeconds(60)));

    // Through ingest(), not ingestOnce(): the limiter is consumed outside the retried unit, so a
    // rolled-back batch is not charged twice and a legitimate device is not throttled for traffic
    // it never sent.
    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class,
            () -> service.ingest(CALLER, batch(message(), message(), message())));

    assertEquals(ErrorCode.INSIGHTS_INGEST_RATE_LIMITED, thrown.getErrorCode());
    assertNotNull(thrown.getRetryAfterSeconds());
    verify(compliance).raiseIngestRateLimited(anyString());
    verify(transactions, never()).save(any());
  }

  // ---------------------------------------------------------------------------------
  // Cache, device state and template staleness
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("The cache is cleared once per distinct date, not once per message")
  void cacheIsInvalidatedPerDistinctDate() {
    stubHappyPath();

    IngestMessage sameDay = message();
    sameDay.setIdempotencyKey("sha256:3333333333333333333333333333");
    IngestMessage otherDay = message();
    otherDay.setIdempotencyKey("sha256:4444444444444444444444444444");
    otherDay.setTxnDate(LocalDate.now().minusDays(3));

    service.ingestOnce(CALLER, batch(message(), sameDay, otherDay));

    // Three messages, two dates. One UPDATE per date, not per row.
    verify(caches, times(2)).invalidateCovering(eq(USER), any(LocalDate.class));
  }

  @Test
  @DisplayName("A device behind on templates is told the server's version; an up-to-date one is not")
  void templateStalenessIsReportedOnlyWhenBehind() {
    stubHappyPath();
    when(templates.findCurrentTemplateVersion()).thenReturn(7);

    IngestBatchRequest behind = batch(message());
    behind.setTemplateVersion(5);
    assertEquals(7, service.ingestOnce(CALLER, behind).getCurrentTemplateVersion());

    IngestMessage next = message();
    next.setIdempotencyKey("sha256:5555555555555555555555555555");
    IngestBatchRequest current = batch(next);
    current.setTemplateVersion(7);
    // Absent rather than equal, so a client that ignores the field entirely still behaves.
    assertNull(service.ingestOnce(CALLER, current).getCurrentTemplateVersion());
  }

  @Test
  @DisplayName("The device's progress counter advances by what was accepted, not what was sent")
  void deviceProgressCountsAcceptedOnly() {
    stubHappyPath();

    IngestMessage leaked = message();
    leaked.setIdempotencyKey("sha256:6666666666666666666666666666");
    leaked.setMerchant("Your OTP is 4821");

    service.ingestOnce(CALLER, batch(message(), leaked));

    ArgumentCaptor<BiDeviceSyncState> captor = ArgumentCaptor.forClass(BiDeviceSyncState.class);
    verify(syncStates).save(captor.capture());
    assertEquals(1L, captor.getValue().getLastIngestSeq());
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private void stubHappyPath() {
    stubNoExistingTransactions();
    when(categorisation.categorise(any(CategorisationInput.class)))
        .thenReturn(BiCategory.builder().id(GROCERIES).code("GROCERIES").build());
    when(transactions.save(any(BiTransaction.class)))
        .thenAnswer(
            invocation -> {
              BiTransaction txn = invocation.getArgument(0);
              txn.setId(UUID.randomUUID());
              return txn;
            });
    stubDeviceState();
  }

  /**
   * The reads every batch performs before it decides anything.
   *
   * <p>{@code requireActiveConsent} is void and does nothing by default, which is the granted case.
   * {@code findCurrentTemplateVersion} is deliberately not stubbed here: Mockito answers 0 for an
   * int, which is what every test except the staleness one wants, and stubbing it in a shared
   * fixture would make that test re-stub — which strict stubs reports as an unnecessary stubbing.
   */
  private void stubNoExistingTransactions() {
    when(transactions.findAllByUserIdAndIdempotencyKeyIn(eq(USER), any())).thenReturn(List.of());
  }

  private void stubDeviceState() {
    when(syncStates.findByUserIdAndDeviceId(eq(USER), anyString())).thenReturn(Optional.empty());
    when(syncStates.save(any(BiDeviceSyncState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  private BiTransaction captureSaved() {
    ArgumentCaptor<BiTransaction> captor = ArgumentCaptor.forClass(BiTransaction.class);
    verify(transactions, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }

  private IngestMessage message() {
    return IngestMessage.builder()
        .idempotencyKey("sha256:aaaabbbbccccddddeeeeffff0000")
        .bankCode("COMB")
        .messageType("CARD_PURCHASE")
        .amount(new BigDecimal("450.00"))
        .currency("LKR")
        .direction("DEBIT")
        .merchant("Coffee Bean")
        .txnDate(LocalDate.now())
        .instrumentType("CARD")
        .maskedTail("4821")
        .build();
  }

  private IngestBatchRequest batch(IngestMessage... messages) {
    return IngestBatchRequest.builder()
        .deviceId("device-abc-123")
        .templateVersion(0)
        .messages(new java.util.ArrayList<>(List.of(messages)))
        .build();
  }
}
