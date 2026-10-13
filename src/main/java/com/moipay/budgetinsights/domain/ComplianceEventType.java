package com.moipay.budgetinsights.domain;

/**
 * Compliance events recorded for Budget Insights.
 *
 * <p>OTP_DETECTED and PIN_DETECTED exist because Compliance requires a detected OTP or PIN capture
 * to be logged and treated as a formal incident even though the message itself is discarded
 * immediately and never stored or transmitted.
 */
public enum ComplianceEventType {
  OTP_DETECTED,
  PIN_DETECTED,
  SENDER_REJECTED,
  CONSENT_GRANTED,
  CONSENT_WITHDRAWN,
  CATEGORY_OVERRIDDEN,
  EXPORT_REQUESTED,
  ERASURE_COMPLETED,
  INGEST_RATE_LIMITED
}
