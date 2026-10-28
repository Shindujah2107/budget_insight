package com.moneyreserve.service.impl;

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
import com.moneyreserve.service.ConsentService;
import com.moneyreserve.service.InsightsComplianceService;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R3 — the consent record and the rules around it.
 *
 * <p>Three decisions shape everything here.
 *
 * <ul>
 *   <li><b>Append-only.</b> Nothing is ever updated. A grant is a row, a withdrawal is a row, and
 *       the latest row is the truth. An UPDATE would make the question "what did they agree to in
 *       August" unanswerable, which is the one question this table exists to answer.
 *   <li><b>A grant must name the current policy version.</b> Consent to superseded text is not
 *       consent to the text in force, so a stale version is refused rather than accepted and
 *       quietly treated as current.
 *   <li><b>Both operations are idempotent.</b> A phone that retries a grant, or a customer who taps
 *       Withdraw twice, must not fill the history with rows that record no change of mind.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentServiceImpl implements ConsentService {

  private final InsightsProperties properties;
  private final BiConsentRepository consents;
  private final InsightsComplianceService compliance;

  // ---------------------------------------------------------------------------------
  // Grant
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public ConsentResponse grant(Caller caller, ConsentGrantRequest request) {
    UUID userId = caller.userId();
    String version = trim(request.getConsentVersion());
    String policyHash = trim(request.getPolicyHash());
    ConsentChannel channel = channel(request.getChannel());

    requireCurrentVersion(version);

    Optional<BiConsent> latest = consents.findFirstByUserIdOrderByCreatedAtDesc(userId);
    if (latest.isPresent() && unchangedGrant(latest.get(), version, policyHash)) {
      // The same consent, again. A retry after a dropped response, or an app that re-grants on
      // every launch. Writing a second identical row would turn the history into noise without
      // recording a single decision the customer made.
      log.info(
          "Insights consent re-grant ignored userId={}: already in force at version {}",
          caller.userId(),
          version);
      return toResponse(latest.get());
    }

    BiConsent granted = new BiConsent();
    granted.setUserId(userId);
    granted.setConsentVersion(version);
    granted.setPolicyHash(policyHash);
    granted.setChannel(channel);
    granted.setGrantedAt(OffsetDateTime.now());
    BiConsent saved = consents.save(granted);

    compliance.raiseConsentChange(ComplianceEventType.CONSENT_GRANTED, null);

    // Version and channel only. The policy hash is not secret, but it is evidence, and evidence
    // belongs in the table that is retained for it rather than in an application log that is not.
    log.info("Insights consent granted userId={} version={} via {}", caller.userId(), version, channel);
    return toResponse(saved);
  }

  // ---------------------------------------------------------------------------------
  // Read
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public ConsentResponse current(Caller caller) {
    Optional<BiConsent> latest = consents.findFirstByUserIdOrderByCreatedAtDesc(caller.userId());
    log.info(
        "Insights consent read userId={} state={}",
        caller.userId(),
        latest.map(c -> c.isActive() ? "ACTIVE v" + c.getConsentVersion() : "WITHDRAWN").orElse("NEVER_GRANTED"));
    return latest.map(this::toResponse).orElseGet(this::neverGranted);
  }

  // ---------------------------------------------------------------------------------
  // Withdraw
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public ConsentResponse withdraw(Caller caller, String channel) {
    Optional<BiConsent> latest =
        consents.findFirstByUserIdOrderByCreatedAtDesc(caller.userId());

    if (latest.isEmpty() || !latest.get().isActive()) {
      // Nothing in force, so nothing to take back. Answering with the current position rather than
      // an error: the customer asked for consent to be off, and consent is off.
      log.info(
          "Insights consent withdrawal ignored userId={}: no consent currently in force",
          caller.userId());
      return latest.map(this::toResponse).orElseGet(this::neverGranted);
    }

    BiConsent inForce = latest.get();
    BiConsent withdrawal = new BiConsent();
    withdrawal.setUserId(inForce.getUserId());
    withdrawal.setConsentVersion(inForce.getConsentVersion());
    withdrawal.setPolicyHash(inForce.getPolicyHash());
    withdrawal.setChannel(channel == null || channel.isBlank()
        ? inForce.getChannel()
        : channel(channel));
    // The grant timestamp is carried across so the withdrawal row records the consent it ended,
    // not merely that one ended. isActive() still reads false, because withdrawnAt is set.
    withdrawal.setGrantedAt(inForce.getGrantedAt());
    withdrawal.setWithdrawnAt(OffsetDateTime.now());
    BiConsent saved = consents.save(withdrawal);

    compliance.raiseConsentChange(
        ComplianceEventType.CONSENT_WITHDRAWN, null);

    log.info(
        "Insights consent withdrawn userId={}; it was in force at version {}",
        caller.userId(),
        inForce.getConsentVersion());
    return toResponse(saved);
  }

  // ---------------------------------------------------------------------------------
  // The gate
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public void requireActiveConsent(UUID userId) {
    BiConsent latest =
        consents
            .findFirstByUserIdOrderByCreatedAtDesc(userId)
            .orElseThrow(
                () -> {
                  log.info("Insights consent check failed userId={}: never granted", userId);
                  return new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_REQUIRED);
                });

    if (!latest.isActive()) {
      log.info("Insights consent check failed userId={}: withdrawn", userId);
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_REQUIRED);
    }
    if (!currentVersion().equals(latest.getConsentVersion())) {
      // Consent to text that has since been replaced. Refused rather than honoured: the customer
      // agreed to something, but not to this.
      log.info(
          "Insights consent refused userId={}: granted at version {}, current version is {}",
          userId,
          latest.getConsentVersion(),
          currentVersion());
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_VERSION_MISMATCH);
    }
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private void requireCurrentVersion(String version) {
    if (!currentVersion().equals(version)) {
      log.info(
          "Insights consent grant refused: offered version {}, current version is {}",
          version,
          currentVersion());
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_CONSENT_VERSION_MISMATCH);
    }
  }

  private boolean unchangedGrant(BiConsent latest, String version, String policyHash) {
    return latest.isActive()
        && Objects.equals(latest.getConsentVersion(), version)
        && Objects.equals(latest.getPolicyHash(), policyHash);
  }

  /**
   * Parses a channel, or refuses the request as malformed.
   *
   * <p>Deliberately <b>not</b> {@code INSIGHTS_CONSENT_REQUIRED}. That code means "the customer has
   * not consented", and a client receiving it sends them to the consent screen. A mistyped query
   * parameter on a withdrawal would then push a customer who already holds consent into granting it
   * again, while the withdrawal they asked for went unrecorded. An IllegalArgumentException is
   * mapped to a plain 400 by the global handler, which is what this is.
   */
  private ConsentChannel channel(String raw) {
    try {
      return ConsentChannel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IllegalArgumentException("The consent channel must be ANDROID, IOS or WEB.");
    }
  }

  private String currentVersion() {
    return properties.getConsent().getVersion();
  }

  private ConsentResponse neverGranted() {
    return ConsentResponse.builder()
        .consentGiven(false)
        .currentVersion(currentVersion())
        .build();
  }

  private ConsentResponse toResponse(BiConsent consent) {
    return ConsentResponse.builder()
        .consentGiven(consent.isActive())
        .currentVersion(currentVersion())
        .consentVersion(consent.getConsentVersion())
        .upToDate(currentVersion().equals(consent.getConsentVersion()))
        .policyHash(consent.getPolicyHash())
        .channel(consent.getChannel() == null ? null : consent.getChannel().name())
        .grantedAt(consent.getGrantedAt())
        .withdrawnAt(consent.getWithdrawnAt())
        .build();
  }

  private static String trim(String raw) {
    return raw == null ? null : raw.trim();
  }
}
