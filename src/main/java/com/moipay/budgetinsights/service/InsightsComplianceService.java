package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.domain.ComplianceEventAction;
import com.moipay.budgetinsights.domain.ComplianceEventSource;
import com.moipay.budgetinsights.domain.ComplianceEventType;
import java.util.UUID;

/**
 * Records that something happened which Compliance must be able to see.
 *
 * <p>Every method here records an event <b>about</b> a detection. The thing detected — a message
 * body, a code — is destroyed before any of this runs, and {@code bi_compliance_event} has four
 * payload columns and no foreign key to the user, so it is physically incapable of holding message
 * content or a financial value.
 *
 * <p>Nothing here throws into its caller. A failed audit write must never become a failed customer
 * request.
 */
public interface InsightsComplianceService {

  void raiseOtpDetected(ComplianceEventSource source, String deviceId, UUID tenantId);

  void raiseSenderRejected(String deviceId, UUID tenantId);

  void raiseConsentChange(ComplianceEventType type, String deviceId, UUID tenantId);

  void raiseCategoryOverridden(UUID tenantId);

  /** A device exceeded the ingest rate limit and its batch was refused. */
  void raiseIngestRateLimited(String deviceId, UUID tenantId);

  void raise(
      ComplianceEventType type,
      ComplianceEventSource source,
      ComplianceEventAction action,
      String deviceId,
      UUID tenantId);
}
