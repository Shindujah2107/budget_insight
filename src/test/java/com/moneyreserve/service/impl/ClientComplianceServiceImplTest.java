package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiComplianceEvent;
import com.moneyreserve.domain.ComplianceEventAction;
import com.moneyreserve.domain.ComplianceEventSource;
import com.moneyreserve.domain.ComplianceEventType;
import com.moneyreserve.dto.ClientComplianceEvent;
import com.moneyreserve.dto.ComplianceEventBatchRequest;
import com.moneyreserve.dto.ComplianceEventBatchResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiComplianceEventRepository;
import com.moneyreserve.service.ConsentService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The rules that decide what a handset is allowed to write into the compliance record.
 *
 * <p>Three of them matter more than the rest, and all three are silent when broken. A device that
 * could assert any event type could file a false entry in the one table that is never purged. A
 * client reference stored as sent would turn the one free string on the contract into a place to
 * put a code. And a report that answered {@code RECORDED} after a failed write would let the device
 * clear its queue over an incident that exists nowhere.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Device-reported compliance events")
class ClientComplianceServiceImplTest {

  @Mock private BiComplianceEventRepository repository;
  @Mock private ConsentService consent;

  private InsightsProperties properties;
  private ClientComplianceServiceImpl service;
  private Caller caller;

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    properties.setLookbackMonths(6);
    properties.setZone("Asia/Colombo");
    properties.setDeviceHashSalt("unit-test-salt");
    properties.getCompliance().setMaxBatchSize(200);
    properties.getCompliance().setRateLimitMessages(600);
    properties.getCompliance().setRateLimitWindowSeconds(60);
    properties.getCompliance().setClockSkewMinutes(10);

    service =
        new ClientComplianceServiceImpl(
            properties, repository, consent, new DeviceIdHasher(properties));
    caller = new Caller(UUID.randomUUID());
  }

  private ClientComplianceEvent event(String type, OffsetDateTime at, String ref) {
    return ClientComplianceEvent.builder().eventType(type).detectedAt(at).clientRef(ref).build();
  }

  private ComplianceEventBatchRequest batch(ClientComplianceEvent... events) {
    return ComplianceEventBatchRequest.builder()
        .deviceId("unit-test-device")
        .platform("ANDROID")
        .events(new ArrayList<>(List.of(events)))
        .build();
  }

  @SuppressWarnings("unchecked")
  private List<BiComplianceEvent> captureSaved() {
    ArgumentCaptor<List<BiComplianceEvent>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).saveAll(captor.capture());
    return captor.getValue();
  }

  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("The server sets the source and the action; the client sets neither")
  void theClientAssertsNothingThatMatters() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime detectedAt = OffsetDateTime.now().minusHours(1);

    ComplianceEventBatchResponse response =
        service.record(caller, batch(event("OTP_DETECTED", detectedAt, "clientref00000001")));

    assertEquals(1, response.getRecorded());
    BiComplianceEvent saved = captureSaved().get(0);
    assertEquals(ComplianceEventSource.SMS_PARSER, saved.getSource());
    assertEquals(ComplianceEventAction.DISCARDED, saved.getAction());
    assertEquals(ComplianceEventType.OTP_DETECTED, saved.getEventType());
    assertEquals(detectedAt.toInstant(), saved.getOccurredAt().toInstant());
  }

  @Test
  @DisplayName("The client reference is stored as a digest, never as the value")
  void theReferenceIsHashed() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    String ref = "plaintextref0001";

    service.record(caller, batch(event("PIN_DETECTED", OffsetDateTime.now().minusMinutes(3), ref)));

    String hash = captureSaved().get(0).getClientRefHash();
    assertNotNull(hash);
    // Hex of a SHA-256 and nothing else. Asserting "does not contain the reference" after this
    // would assert nothing: no hex string can contain a reference with a letter outside a-f in it.
    assertEquals(64, hash.length());
    assertTrue(hash.matches("[0-9a-f]{64}"));
    assertNotEquals(ref, hash);
  }

  @Test
  @DisplayName("Only OTP_DETECTED and PIN_DETECTED may be asserted by a device")
  void everyOtherTypeIsRejected() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);

    ComplianceEventBatchResponse response =
        service.record(
            caller,
            batch(
                event("CONSENT_GRANTED", now, "refconsentgranted"),
                event("ERASURE_COMPLETED", now, "referasuredone01"),
                event("SENDER_REJECTED", now, "refsenderreject1"),
                event("NOT_AN_ENUM_VALUE", now, "refnotanenumval1"),
                event("OTP_DETECTED", now, "refgoodotpevent1")));

    assertEquals(4, response.getRejected());
    assertEquals(1, response.getRecorded(), "A bad row must not cost the good one");
    assertEquals(1, captureSaved().size());
    response.getResults().stream()
        .filter(r -> "REJECTED".equals(r.getStatus()))
        .forEach(
            r ->
                assertEquals(
                    ErrorCode.INSIGHTS_COMPLIANCE_EVENT_NOT_REPORTABLE.getCode(), r.getError()));
  }

  @Test
  @DisplayName("Event types are matched case-insensitively, but only against the reportable two")
  void caseDoesNotDecideAdmissibility() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());

    ComplianceEventBatchResponse response =
        service.record(
            caller,
            batch(
                event("otp_detected", OffsetDateTime.now().minusMinutes(2), "reflowercase001"),
                event(" Pin_Detected ", OffsetDateTime.now().minusMinutes(2), "refmixedcase001")));

    assertEquals(2, response.getRecorded());
    assertEquals(0, response.getRejected());
  }

  @Test
  @DisplayName("A clock too far ahead, or a date outside the lookback, is refused")
  void implausibleTimestampsAreRefused() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());

    ComplianceEventBatchResponse response =
        service.record(
            caller,
            batch(
                event("OTP_DETECTED", OffsetDateTime.now().plusHours(2), "reffuture0000001"),
                event("OTP_DETECTED", OffsetDateTime.now().minusMonths(9), "refancient000001"),
                // Inside the ten-minute skew allowance: a phone whose clock runs a little fast is
                // an ordinary phone, not a bad actor.
                event("OTP_DETECTED", OffsetDateTime.now().plusMinutes(5), "refslightskew001")));

    assertEquals(2, response.getRejected());
    assertEquals(1, response.getRecorded());
    response.getResults().stream()
        .filter(r -> "REJECTED".equals(r.getStatus()))
        .forEach(
            r ->
                assertEquals(
                    ErrorCode.INSIGHTS_COMPLIANCE_TIMESTAMP_INVALID.getCode(), r.getError()));
  }

  @Test
  @DisplayName("The same reference twice in one batch is one incident")
  void inBatchDuplicatesCollapse() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(4);

    ComplianceEventBatchResponse response =
        service.record(
            caller,
            batch(
                event("OTP_DETECTED", now, "refrepeated00001"),
                event("OTP_DETECTED", now, "refrepeated00001")));

    assertEquals(1, response.getRecorded());
    assertEquals(1, response.getDuplicates());
    assertEquals(1, captureSaved().size());
  }

  @Test
  @DisplayName("A reference already reported writes nothing")
  void alreadyReportedWritesNothing() {
    String ref = "refalreadyseen01";
    DeviceIdHasher hasher = new DeviceIdHasher(properties);
    String hash = hasher.hashClientRef(hasher.hashDeviceId("unit-test-device"), ref);
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of(hash));

    ComplianceEventBatchResponse response =
        service.record(
            caller, batch(event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(6), ref)));

    assertEquals(0, response.getRecorded());
    assertEquals(1, response.getDuplicates());
    verify(repository, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("Consent is checked before anything is written")
  void consentGatesTheWholeBatch() {
    doThrow(new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_REQUIRED))
        .when(consent)
        .requireActiveConsent(caller.userId());

    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.record(
                    caller,
                    batch(
                        event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(1), "refnoconsent1"))));

    assertEquals(ErrorCode.INSIGHTS_CONSENT_REQUIRED, thrown.getErrorCode());
    verify(repository, never()).saveAll(anyList());
  }

  @Test
  @DisplayName("Over the rate limit the batch is refused with a Retry-After the client can use")
  void rateLimitRefusesTheBatch() {
    properties.getCompliance().setRateLimitMessages(2);
    service =
        new ClientComplianceServiceImpl(
            properties, repository, consent, new DeviceIdHasher(properties));
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);

    service.record(
        caller,
        batch(
            event("OTP_DETECTED", now, "refwithinlimit01"),
            event("OTP_DETECTED", now, "refwithinlimit02")));

    BudgetInsightsException thrown =
        assertThrows(
            BudgetInsightsException.class,
            () ->
                service.record(
                    caller, batch(event("OTP_DETECTED", now, "refoverthelimit1"))));

    assertEquals(ErrorCode.INSIGHTS_COMPLIANCE_RATE_LIMITED, thrown.getErrorCode());
    assertNotNull(thrown.getRetryAfterSeconds());
    assertTrue(thrown.getRetryAfterSeconds() > 0);
  }

  @Test
  @DisplayName("A failed write reaches the caller — it is never reported as RECORDED")
  void aFailedWriteIsNotSwallowed() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    doThrow(new IllegalStateException("database unavailable")).when(repository).saveAll(anyList());

    assertThrows(
        IllegalStateException.class,
        () ->
            service.record(
                caller,
                batch(event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(1), "refwritefails1"))),
        "Answering RECORDED for a row that was never written is how an incident disappears");
  }

  @Test
  @DisplayName("With no salt configured, nothing is recorded — an unsalted digest is not a digest")
  void noSaltRefusesToRecord() {
    properties.setDeviceHashSalt("");
    service =
        new ClientComplianceServiceImpl(
            properties, repository, consent, new DeviceIdHasher(properties));

    // The controller answers 501 before reaching the service in this state, so this is the
    // second line of defence rather than the first. It is here because the alternative -- carrying on
    // with a reversible digest of a client-supplied string, in the one table that is never
    // purged -- is the failure this whole contract exists to make impossible.
    assertThrows(
        IllegalStateException.class,
        () ->
            service.record(
                caller,
                batch(event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(1), "refnosaltset01"))),
        "An unsalted client-reference digest must never be written");
    verify(repository, never()).saveAll(anyList());
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("The same reference from two different handsets is two incidents, not a duplicate")
  void theDedupKeyIsScopedToTheDevice() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(7);
    String shared = "sharedclientref1";

    ComplianceEventBatchRequest first =
        ComplianceEventBatchRequest.builder()
            .deviceId("handset-one")
            .events(new ArrayList<>(List.of(event("OTP_DETECTED", now, shared))))
            .build();
    ComplianceEventBatchRequest second =
        ComplianceEventBatchRequest.builder()
            .deviceId("handset-two")
            .events(new ArrayList<>(List.of(event("OTP_DETECTED", now, shared))))
            .build();

    service.record(caller, first);
    service.record(caller, second);

    // Two saves, and two different digests. clientRef is only constrained to an identifier shape,
    // so a handset is free to send a local counter -- and if the key were global the second
    // customer's genuine detection would come back DUPLICATE, never be written, and be cleared
    // from their queue on the strength of that answer.
    ArgumentCaptor<List<BiComplianceEvent>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository, times(2)).saveAll(captor.capture());
    List<List<BiComplianceEvent>> saves = captor.getAllValues();
    assertEquals(1, saves.get(0).size());
    assertEquals(1, saves.get(1).size());
    assertNotEquals(
        saves.get(0).get(0).getClientRefHash(),
        saves.get(1).get(0).getClientRefHash(),
        "Two handsets sending the same reference must not collide");
  }

  @Test
  @DisplayName("A structurally malformed event costs its own slot, never the batch")
  void aMalformedEventDoesNotFailTheBatch() {
    when(repository.findExistingClientRefHashes(anyCollection())).thenReturn(List.of());
    OffsetDateTime now = OffsetDateTime.now().minusMinutes(1);

    ComplianceEventBatchResponse response =
        service.record(
            caller,
            batch(
                event("OTP_DETECTED", now, "short"), // fails the reference pattern
                event("OTP_DETECTED", now, "has:a:colon:in:it"), // same
                event("OTP_DETECTED", now, null), // no reference at all
                event("OTP_DETECTED", null, "refnodetectedat1"), // no timestamp
                event(null, now, "refnoeventtype01"), // no type
                event("OTP_DETECTED", now, "refthegoodrow001")));

    assertEquals(5, response.getRejected());
    assertEquals(1, response.getRecorded(), "The good row must survive its neighbours");
    assertEquals(1, captureSaved().size());
    assertEquals(6, response.getResults().size(), "Every submitted event needs a result");
  }
}
