package com.moneyreserve.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * MoneyReserve error codes ({@code MR.<area>.E.<nnn>}), as the app sees them in {@code
 * ApiError.error}. The code is the contract; the message may be reworded.
 */
@Getter
public enum ErrorCode {

  // Areas: ING = ingest, REF = reference sync, TXN = transaction, CAT = category, BGT = budget, CNS = consent, DAT = data rights,
  // CMP = device-side compliance reporting.
  //
  // Messages here are deliberately plain and non-diagnostic. In particular
  // INSIGHTS_OTP_PAYLOAD_REJECTED does not say that an OTP was detected: the caller is a
  // device that should already have filtered it, and confirming the classification back
  // over the wire tells an attacker which payloads trip the filter. The detail belongs in
  // the compliance event, not in the response.
  //
  // Two states from the specification are intentionally absent. A duplicate ingest is
  // reported per message inside a successful batch, and "no budget set" is a normal state
  // on the summary response. Neither is an error and neither should be raised as an
  // exception.
  // ---------------------------------------------------------------------------------

  // Ingest
  INSIGHTS_OTP_PAYLOAD_REJECTED(
      "MR.ING.E.001", HttpStatus.UNPROCESSABLE_ENTITY, "This message could not be processed."),
  INSIGHTS_LOOKBACK_EXCEEDED(
      "MR.ING.E.002",
      HttpStatus.BAD_REQUEST,
      "That date is outside the period Budget Insights covers."),
  INSIGHTS_INGEST_RATE_LIMITED(
      "MR.ING.E.003",
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many updates at once. Please try again shortly."),
  // Reported per message inside a successful batch rather than thrown, so the device can drop the
  // one message its parser got wrong and keep the rest of its queue. The message is deliberately
  // incurious: a device that sent an unusable shape does not need to be told which field gave it
  // away, and neither does anyone reading the response over the wire.
  INSIGHTS_INGEST_MESSAGE_INVALID(
      "MR.ING.E.004",
      HttpStatus.BAD_REQUEST,
      "This message could not be read. Please try again later."),

  // Reference sync
  INSIGHTS_TEMPLATE_VERSION_STALE(
      "MR.REF.E.001",
      HttpStatus.CONFLICT,
      "Your app needs to refresh its settings before syncing again."),

  // Transactions
  INSIGHTS_TXN_NOT_FOUND(
      "MR.TXN.E.001", HttpStatus.NOT_FOUND, "We couldn't find that transaction."),
  INSIGHTS_TXN_NOT_EDITABLE(
      "MR.TXN.E.002", HttpStatus.CONFLICT, "That transaction can no longer be changed."),
  INSIGHTS_ACCOUNT_NOT_FOUND(
      "MR.TXN.E.003", HttpStatus.NOT_FOUND, "We couldn't find that account."),

  // Categories
  INSIGHTS_CATEGORY_INVALID(
      "MR.CAT.E.001", HttpStatus.BAD_REQUEST, "Please choose a category from the list."),

  // Budget
  INSIGHTS_BUDGET_INVALID_PERIOD(
      "MR.BGT.E.001", HttpStatus.BAD_REQUEST, "Please choose a valid budget period."),
  INSIGHTS_BUDGET_LIMIT_INVALID(
      "MR.BGT.E.002",
      HttpStatus.BAD_REQUEST,
      "Please enter a budget amount greater than zero."),

  // Consent
  INSIGHTS_CONSENT_REQUIRED(
      "MR.CNS.E.001",
      HttpStatus.FORBIDDEN,
      "Please allow Budget Insights to read your bank messages before continuing."),
  INSIGHTS_CONSENT_VERSION_MISMATCH(
      "MR.CNS.E.002",
      HttpStatus.CONFLICT,
      "Our terms have been updated. Please review and accept them to continue."),

  // Data rights
  INSIGHTS_EXPORT_NOT_FOUND(
      "MR.DAT.E.001",
      HttpStatus.NOT_FOUND,
      "We couldn't find that export."),
  // 410 rather than 404: the difference between "there was never one" and "there was, and it has
  // gone" is the difference between a client bug and a customer who waited too long, and only one
  // of those is worth telling them to request a fresh one.
  INSIGHTS_EXPORT_EXPIRED(
      "MR.DAT.E.002",
      HttpStatus.GONE,
      "That export has expired. Please request a new one."),

  // Device-side compliance reporting (BI-36). CMP = compliance.
  //
  // These belong to
  // the route a handset uses to report an OTP or PIN it destroyed, and they follow the ingest
  // codes' rule about saying as little as possible: a device is told its report could not be
  // read, never which of its events looked wrong and why, because the caller is the component
  // whose filter is under scrutiny.
  INSIGHTS_COMPLIANCE_BATCH_INVALID(
      "MR.CMP.E.001",
      HttpStatus.BAD_REQUEST,
      "This report could not be read. Please try again later."),
  INSIGHTS_COMPLIANCE_RATE_LIMITED(
      "MR.CMP.E.002",
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many updates at once. Please try again shortly."),
  // Per event, not per request: one unreportable row must not cost the device the rest of its
  // queue, which it would then resend in full, including the row that will fail again.
  INSIGHTS_COMPLIANCE_EVENT_NOT_REPORTABLE(
      "MR.CMP.E.003",
      HttpStatus.BAD_REQUEST,
      "That event cannot be reported from a device."),
  INSIGHTS_COMPLIANCE_TIMESTAMP_INVALID(
      "MR.CMP.E.004",
      HttpStatus.BAD_REQUEST,
      "That date is outside the period Budget Insights covers."),
  // Also per event. A malformed row must not fail the batch: the messages behind these reports
  // were destroyed on the handset, so a queue the device gives up on is a queue of incidents
  // nobody can reconstruct.
  INSIGHTS_COMPLIANCE_EVENT_INVALID(
      "MR.CMP.E.005",
      HttpStatus.BAD_REQUEST,
      "This report could not be read. Please try again later."),

  // SMS format discovery (FMT). Same rule as the compliance codes: a device is told that an item
  // could not be used, never which check it failed -- the handset is the component under
  // scrutiny, and naming the check that tripped is telling it how to avoid it.
  INSIGHTS_FORMAT_BATCH_INVALID(
      "MR.FMT.E.001",
      HttpStatus.BAD_REQUEST,
      "This report could not be read. Please try again later."),
  INSIGHTS_FORMAT_RATE_LIMITED(
      "MR.FMT.E.002",
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many updates at once. Please try again later."),
  // Per item, inside a successful response.
  INSIGHTS_FORMAT_ITEM_INVALID(
      "MR.FMT.E.003", HttpStatus.BAD_REQUEST, "This item could not be read."),
  INSIGHTS_FORMAT_ITEM_NOT_USEFUL(
      "MR.FMT.E.004", HttpStatus.BAD_REQUEST, "This item has too little in it to be used."),

  // Budget Insights admin dashboard (ADM). Staff-facing, so the messages say what to fix.
  // E.002 and E.003 are retired (the Compliance menu was removed before release).
  ADMIN_ROLE_REQUIRED(
      "MR.ADM.E.001", HttpStatus.FORBIDDEN, "Budget Insights admin needs the Super Admin role."),
  ADMIN_CONFIG_WRITE_DISABLED(
      "MR.ADM.E.004", HttpStatus.FORBIDDEN, "Configuration is frozen in this environment."),
  ADMIN_RANGE_INVALID("MR.ADM.E.005", HttpStatus.BAD_REQUEST, "That date range is not valid."),
  ADMIN_FIELD_INVALID(
      "MR.ADM.E.006", HttpStatus.BAD_REQUEST, "Please check the highlighted fields."),
  ADMIN_CONFIG_NOT_FOUND("MR.ADM.E.007", HttpStatus.NOT_FOUND, "That item does not exist."),
  ADMIN_CONFIG_DUPLICATE(
      "MR.ADM.E.008", HttpStatus.CONFLICT, "An item with the same key already exists."),
  ADMIN_CATEGORY_IN_USE(
      "MR.ADM.E.009", HttpStatus.CONFLICT, "This category is in use and cannot be deleted."),
  ADMIN_CHANGESET_STALE(
      "MR.ADM.E.010",
      HttpStatus.CONFLICT,
      "Someone else changed the configuration. Reload and review again."),
  ADMIN_PUBLISH_BLOCKED(
      "MR.ADM.E.011", HttpStatus.UNPROCESSABLE_ENTITY, "Fix the blocking problems first."),
  ADMIN_WARNINGS_NOT_ACKNOWLEDGED(
      "MR.ADM.E.012", HttpStatus.CONFLICT, "Please acknowledge the warnings to publish."),
  ADMIN_PATTERN_INVALID(
      "MR.ADM.E.013",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "The pattern is not valid or will not work on every handset."),
  ADMIN_FIELD_MAP_INVALID(
      "MR.ADM.E.014", HttpStatus.UNPROCESSABLE_ENTITY, "The field map is not valid."),
  ADMIN_SYSTEM_ITEM_LOCKED(
      "MR.ADM.E.015", HttpStatus.FORBIDDEN, "This item is locked and cannot be changed."),
  ADMIN_SENDER_IN_USE(
      "MR.ADM.E.016",
      HttpStatus.CONFLICT,
      "Active SMS templates use this sender. Confirm the impact to continue."),
  ADMIN_NOTHING_TO_PUBLISH(
      "MR.ADM.E.017", HttpStatus.CONFLICT, "There are no changes to publish."),
  ADMIN_SAMPLE_REJECTED(
      "MR.ADM.E.018", HttpStatus.UNPROCESSABLE_ENTITY, "This sample SMS cannot be used."),
  ADMIN_SPEC_LITERAL_PII(
      "MR.ADM.E.019",
      HttpStatus.UNPROCESSABLE_ENTITY,
      "The template would store account-like numbers or an e-mail address as fixed text."),
  ADMIN_SPEC_INCOMPLETE(
      "MR.ADM.E.020", HttpStatus.UNPROCESSABLE_ENTITY, "The template is not complete yet."),
  ADMIN_RULE_NAMES_INVALID(
      "MR.ADM.E.021", HttpStatus.UNPROCESSABLE_ENTITY, "Please check the merchant names."),
  /** Not in the architecture doc's code table: the doc sets the limits (§3.1) but names no code. */
  ADMIN_RATE_LIMITED(
      "MR.ADM.E.022",
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many changes in a short time. Wait a moment and try again."),
  /** A bank still referenced by senders, templates or customer data cannot be removed or turned off. */
  ADMIN_BANK_IN_USE(
      "MR.ADM.E.023",
      HttpStatus.CONFLICT,
      "This bank is still used by SMS senders, templates or customer data."),

  // ---------------------------------------------------------------------------------
  // App sign-in (AUT): mobile number + SMS one-time code, then JWTs issued by this service.
  //
  // The messages are written for the customer and say nothing about which part of a check failed
  // beyond what the customer needs to fix it. Whether a number has an account is disclosed on
  // purpose (register and log-in are separate screens and must say "log in instead"), so it is
  // rate-limited per number rather than hidden.
  AUTH_PHONE_INVALID(
      "MR.AUT.E.001",
      HttpStatus.BAD_REQUEST,
      "Enter a Sri Lankan mobile number, for example +94 77 123 4521."),
  AUTH_PHONE_TAKEN(
      "MR.AUT.E.002",
      HttpStatus.CONFLICT,
      "This number already has an account. Log in instead."),
  AUTH_ACCOUNT_NOT_FOUND(
      "MR.AUT.E.003",
      HttpStatus.NOT_FOUND,
      "There's no account for this number yet. Create one instead."),
  AUTH_CODE_INVALID(
      "MR.AUT.E.004",
      HttpStatus.BAD_REQUEST,
      "That code isn't right. Check the SMS and try again."),
  AUTH_CODE_EXPIRED(
      "MR.AUT.E.005", HttpStatus.GONE, "This code has expired. Send a new one."),
  AUTH_TOO_MANY_ATTEMPTS(
      "MR.AUT.E.006",
      HttpStatus.TOO_MANY_REQUESTS,
      "Too many attempts. Wait a few minutes and try again."),
  AUTH_CODE_RESEND_TOO_SOON(
      "MR.AUT.E.007",
      HttpStatus.TOO_MANY_REQUESTS,
      "Please wait a moment before asking for another code."),
  AUTH_SESSION_EXPIRED(
      "MR.AUT.E.008", HttpStatus.UNAUTHORIZED, "Your session has ended. Please log in again."),
  AUTH_SMS_FAILED(
      "MR.AUT.E.009",
      HttpStatus.SERVICE_UNAVAILABLE,
      "We couldn't send the SMS just now. Please try again shortly."),
  AUTH_DISABLED(
      "MR.AUT.E.010", HttpStatus.NOT_FOUND, "Sign-in is not available.");

  private final String code;
  private final HttpStatus httpStatus;
  private final String defaultMessage;

  ErrorCode(String code, HttpStatus httpStatus, String defaultMessage) {
    this.code = code;
    this.httpStatus = httpStatus;
    this.defaultMessage = defaultMessage;
  }
}
