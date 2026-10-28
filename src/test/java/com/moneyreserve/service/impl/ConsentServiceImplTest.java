package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiConsent;
import com.moneyreserve.domain.ComplianceEventType;
import com.moneyreserve.domain.ConsentChannel;
import com.moneyreserve.dto.ConsentGrantRequest;
import com.moneyreserve.dto.ConsentResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiConsentRepository;
import com.moneyreserve.service.InsightsComplianceService;
import java.time.OffsetDateTime;
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
 * The consent rules that decide whether a record can be trusted months later.
 *
 * <p>Append-only history, a grant that must name the policy actually in force, idempotence on both
 * operations, and a gate that refuses superseded consent. Each of these is silent when broken: the
 * call succeeds and the evidence is wrong.
 */
@ExtendWith(MockitoExtension.class)
class ConsentServiceImplTest {

  private static final UUID USER = UUID.randomUUID();
  private static final String CURRENT = "1.0";
  private static final String HASH = "sha256:0f8c1a...";

  private final Caller caller = new Caller(USER);

  @Mock private BiConsentRepository consents;
  @Mock private InsightsComplianceService compliance;

  private InsightsProperties properties;
  private ConsentServiceImpl service;

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    properties.getConsent().setVersion(CURRENT);
    service = new ConsentServiceImpl(properties, consents, compliance);
  }

  // ---------------------------------------------------------------------------------
  // Grant
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a first grant writes a record carrying the version, the hash and the channel")
  void firstGrantIsRecorded() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.empty());
    saves();

    ConsentResponse out = service.grant(caller, request(CURRENT));

    ArgumentCaptor<BiConsent> saved = ArgumentCaptor.forClass(BiConsent.class);
    verify(consents).save(saved.capture());
    assertEquals(USER, saved.getValue().getUserId());
    assertEquals(CURRENT, saved.getValue().getConsentVersion());
    assertEquals(HASH, saved.getValue().getPolicyHash());
    assertEquals(ConsentChannel.ANDROID, saved.getValue().getChannel());
    assertNull(saved.getValue().getWithdrawnAt());

    assertTrue(out.isConsentGiven());
    assertTrue(out.getUpToDate());
    verify(compliance).raiseConsentChange(eq(ComplianceEventType.CONSENT_GRANTED), any());
  }

  @Test
  @DisplayName("re-granting the same version and text writes nothing")
  void repeatedGrantIsIdempotent() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active(CURRENT, HASH)));

    ConsentResponse out = service.grant(caller, request(CURRENT));

    assertTrue(out.isConsentGiven());
    // A phone retrying after a dropped response must not add a row that records no decision.
    verify(consents, never()).save(any());
    verify(compliance, never()).raiseConsentChange(any(), any());
  }

  @Test
  @DisplayName("a grant naming a superseded policy version is refused, not accepted as current")
  void staleVersionGrantIsRefused() {
    BudgetInsightsException ex =
        assertThrows(
            BudgetInsightsException.class, () -> service.grant(caller, request("0.9")));

    assertEquals(ErrorCode.INSIGHTS_CONSENT_VERSION_MISMATCH, ex.getErrorCode());
    verify(consents, never()).save(any());
  }

  @Test
  @DisplayName("re-granting after the policy text changed writes a new record")
  void newHashAtSameVersionIsRecorded() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active(CURRENT, "sha256:an-older-text")));
    saves();

    service.grant(caller, request(CURRENT));

    // Same version, different text. Not the same consent, so not the same record.
    verify(consents).save(any());
  }

  // ---------------------------------------------------------------------------------
  // Withdraw
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("withdrawal appends a record rather than editing the grant")
  void withdrawalIsAppendOnly() {
    BiConsent inForce = active(CURRENT, HASH);
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.of(inForce));
    saves();

    ConsentResponse out = service.withdraw(caller, null);

    ArgumentCaptor<BiConsent> saved = ArgumentCaptor.forClass(BiConsent.class);
    verify(consents).save(saved.capture());
    // A new row, not the old one mutated -- otherwise "what did they agree to in August" has no
    // answer.
    assertTrue(saved.getValue() != inForce);
    assertEquals(CURRENT, saved.getValue().getConsentVersion());
    assertEquals(inForce.getGrantedAt(), saved.getValue().getGrantedAt());
    assertFalse(saved.getValue().isActive());
    assertNull(inForce.getWithdrawnAt());

    assertFalse(out.isConsentGiven());
    verify(compliance)
        .raiseConsentChange(eq(ComplianceEventType.CONSENT_WITHDRAWN), any());
  }

  @Test
  @DisplayName("withdrawing when nothing is in force is a no-op, not an error")
  void withdrawingTwiceIsIdempotent() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(withdrawn()));

    ConsentResponse out = service.withdraw(caller, null);

    assertFalse(out.isConsentGiven());
    verify(consents, never()).save(any());
    verify(compliance, never()).raiseConsentChange(any(), any());
  }

  @Test
  @DisplayName("withdrawing on a different device records that device, not the granting one")
  void withdrawalChannelOverrides() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active(CURRENT, HASH)));
    saves();

    service.withdraw(caller, "web");

    ArgumentCaptor<BiConsent> saved = ArgumentCaptor.forClass(BiConsent.class);
    verify(consents).save(saved.capture());
    assertEquals(ConsentChannel.WEB, saved.getValue().getChannel());
  }

  @Test
  @DisplayName("a mistyped channel is a bad request, not a demand that they consent again")
  void malformedChannelIsABadRequest() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active(CURRENT, HASH)));

    // Reporting this as INSIGHTS_CONSENT_REQUIRED would send a customer who holds consent back to
    // the consent screen, and the withdrawal they asked for would go unrecorded.
    assertThrows(IllegalArgumentException.class, () -> service.withdraw(caller, "banana"));
    verify(consents, never()).save(any());
    verify(compliance, never()).raiseConsentChange(any(), any());
  }

  // ---------------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a customer who has never consented gets a position, not an error")
  void neverConsentedIsNotAnError() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.empty());

    ConsentResponse out = service.current(caller);

    assertFalse(out.isConsentGiven());
    assertEquals(CURRENT, out.getCurrentVersion());
    assertNull(out.getConsentVersion());
    assertNull(out.getUpToDate());
  }

  @Test
  @DisplayName("consent given against an old policy reads as given but out of date")
  void consentedButOutOfDate() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active("0.9", HASH)));

    ConsentResponse out = service.current(caller);

    // Two different facts. Treating this as "never consented" would misdescribe the customer.
    assertTrue(out.isConsentGiven());
    assertFalse(out.getUpToDate());
    assertEquals("0.9", out.getConsentVersion());
  }

  // ---------------------------------------------------------------------------------
  // The gate
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("the gate refuses when consent was never given")
  void gateRefusesWithoutConsent() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER)).thenReturn(Optional.empty());

    assertEquals(
        ErrorCode.INSIGHTS_CONSENT_REQUIRED,
        assertThrows(BudgetInsightsException.class, () -> service.requireActiveConsent(USER))
            .getErrorCode());
  }

  @Test
  @DisplayName("the gate refuses once consent has been withdrawn")
  void gateRefusesAfterWithdrawal() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(withdrawn()));

    assertEquals(
        ErrorCode.INSIGHTS_CONSENT_REQUIRED,
        assertThrows(BudgetInsightsException.class, () -> service.requireActiveConsent(USER))
            .getErrorCode());
  }

  @Test
  @DisplayName("the gate refuses consent given against a policy that has since been replaced")
  void gateRefusesStaleVersion() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active("0.9", HASH)));

    assertEquals(
        ErrorCode.INSIGHTS_CONSENT_VERSION_MISMATCH,
        assertThrows(BudgetInsightsException.class, () -> service.requireActiveConsent(USER))
            .getErrorCode());
  }

  @Test
  @DisplayName("the gate passes on consent in force at the current version")
  void gatePasses() {
    when(consents.findFirstByUserIdOrderByCreatedAtDesc(USER))
        .thenReturn(Optional.of(active(CURRENT, HASH)));

    service.requireActiveConsent(USER);
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private void saves() {
    when(consents.save(any(BiConsent.class)))
        .thenAnswer(
            invocation -> {
              BiConsent row = invocation.getArgument(0);
              if (row.getId() == null) {
                // Stands in for @PrePersist, which no mock will run.
                row.setId(UUID.randomUUID());
              }
              return row;
            });
  }

  private ConsentGrantRequest request(String version) {
    return ConsentGrantRequest.builder()
        .consentVersion(version)
        .policyHash(HASH)
        .channel("ANDROID")
        .build();
  }

  private BiConsent active(String version, String hash) {
    BiConsent row = new BiConsent();
    row.setId(UUID.randomUUID());
    row.setUserId(USER);
    row.setConsentVersion(version);
    row.setPolicyHash(hash);
    row.setChannel(ConsentChannel.ANDROID);
    row.setGrantedAt(OffsetDateTime.now().minusDays(3));
    return row;
  }

  private BiConsent withdrawn() {
    BiConsent row = active(CURRENT, HASH);
    row.setWithdrawnAt(OffsetDateTime.now().minusDays(1));
    return row;
  }
}
