package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.domain.ComplianceEventAction;
import com.moipay.budgetinsights.domain.ComplianceEventSource;
import com.moipay.budgetinsights.domain.ComplianceEventType;
import com.moipay.budgetinsights.domain.BiComplianceEvent;
import com.moipay.budgetinsights.repository.BiComplianceEventRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.moipay.budgetinsights.service.InsightsComplianceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records Budget Insights compliance events.
 *
 * <p>Compliance requires that a detected OTP or PIN capture is logged and treated as a formal
 * incident even though the message is discarded immediately and its value never stored or
 * transmitted. This service is how that requirement is met.
 *
 * <p>Two deliberate design choices, both of which matter more than they look:
 *
 * <p><b>Every public method carries {@link Propagation#REQUIRES_NEW}, not only {@code raise}.</b>
 * The convenience methods delegate to {@code raise} on {@code this}, which goes straight to the
 * method and never through the Spring proxy, so an annotation on {@code raise} alone would apply
 * only to a caller that invoked it directly -- and none do. Annotated one layer out, the new
 * transaction begins at the call the proxy actually intercepts.
 *
 * <ul>
 *   <li><b>{@link Propagation#REQUIRES_NEW}.</b> The common case for raising an OTP event is that
 *       the surrounding ingest is about to be <em>rejected</em>. If the event shared that
 *       transaction it would roll back with it, and the incident that triggered the rejection would
 *       leave no record at all. The event is written in its own transaction so it survives the
 *       caller failing.
 *   <li><b>Never throws into the caller.</b> A failure to record must not convert a correctly
 *       rejected payload into a server error, or mask the original reason. Failures are logged at
 *       ERROR for alerting instead. This is a real trade-off — see the note on {@code raise}.
 * </ul>
 *
 * <p>Nothing passed to this service may carry message content or a financial value. The entity has
 * no column for either, so a mistake here cannot become a leak, but callers should not try.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsComplianceServiceImpl implements InsightsComplianceService {

  private final BiComplianceEventRepository repository;
  private final DeviceIdHasher hasher;

  /** An OTP or PIN was detected and discarded. The value itself is never passed in. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raiseOtpDetected(ComplianceEventSource source, String deviceId, UUID tenantId) {
    raise(
        ComplianceEventType.OTP_DETECTED,
        source,
        ComplianceEventAction.DISCARDED,
        deviceId,
        tenantId);
  }

  /**
   * A device exceeded the ingest rate limit and its batch was refused.
   *
   * <p>Recorded as an incident rather than only a metric because a handset ingesting far faster
   * than a person can spend is either a looping client or a device that is not behaving as the
   * consent the customer gave describes, and Compliance is entitled to see both.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raiseIngestRateLimited(String deviceId, UUID tenantId) {
    raise(
        ComplianceEventType.INGEST_RATE_LIMITED,
        ComplianceEventSource.API_GATEWAY,
        ComplianceEventAction.THROTTLED,
        deviceId,
        tenantId);
  }

  /** A message arrived from a sender that is not on the allowlist. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raiseSenderRejected(String deviceId, UUID tenantId) {
    raise(
        ComplianceEventType.SENDER_REJECTED,
        ComplianceEventSource.API_GATEWAY,
        ComplianceEventAction.REJECTED,
        deviceId,
        tenantId);
  }

  /** Consent was granted or withdrawn. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raiseConsentChange(ComplianceEventType type, String deviceId, UUID tenantId) {
    raise(type, ComplianceEventSource.APP, ComplianceEventAction.RECORDED, deviceId, tenantId);
  }

  /** The customer moved a transaction to a different category. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raiseCategoryOverridden(UUID tenantId) {
    raise(
        ComplianceEventType.CATEGORY_OVERRIDDEN,
        ComplianceEventSource.APP,
        ComplianceEventAction.RECORDED,
        null,
        tenantId);
  }

  /**
   * Write one compliance event.
   *
   * <p>Swallows its own failures by design: a compliance record that cannot be written must not
   * turn a correctly rejected request into a 500, nor replace the caller's reason for failing with
   * this one. The cost is that a persistent failure here is silent to the customer and visible only
   * in logs, so the ERROR below should be alerted on rather than merely collected.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Override
  public void raise(
      ComplianceEventType type,
      ComplianceEventSource source,
      ComplianceEventAction action,
      String deviceId,
      UUID tenantId) {
    try {
      BiComplianceEvent event =
          BiComplianceEvent.builder()
              .eventType(type)
              .source(source)
              .action(action)
              .occurredAt(OffsetDateTime.now())
              .deviceIdHash(hasher.hashDeviceId(deviceId))
              .tenantId(tenantId)
              .build();
      repository.save(event);
      log.info(
          "Budget Insights compliance event recorded type={} source={} action={} tenant={}",
          type,
          source,
          action,
          tenantId);
    } catch (RuntimeException ex) {
      log.error(
          "Failed to record Budget Insights compliance event type={} source={} action={}",
          type,
          source,
          action,
          ex);
    }
  }
}
