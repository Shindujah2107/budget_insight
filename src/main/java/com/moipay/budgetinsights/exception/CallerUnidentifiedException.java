package com.moipay.budgetinsights.exception;

/**
 * The request is authenticated but carries no customer identity.
 *
 * <p>Reachable only in local direct mode, where HTTP basic authenticates an admin and no gateway
 * headers are present. Core answered this with its own AUTH_UNAUTHORIZED code; that code belongs to
 * core's auth surface and did not move here, so this is the one place where the migration could not
 * keep the error body byte-identical. It answers 401 with error "Unauthorized" rather than
 * borrowing a MOI.BI.* code that would misdescribe it — INSIGHTS_CONSENT_REQUIRED in particular
 * would tell a client to go and collect consent it already has.
 */
public class CallerUnidentifiedException extends RuntimeException {
  public CallerUnidentifiedException(String message) {
    super(message);
  }
}
