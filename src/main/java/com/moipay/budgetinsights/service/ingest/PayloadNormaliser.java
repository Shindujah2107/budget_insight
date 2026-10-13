package com.moipay.budgetinsights.service.ingest;

import com.moipay.budgetinsights.domain.InstrumentType;
import com.moipay.budgetinsights.domain.TxnDirection;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns what a device sent into what the schema will accept (part of BI-10).
 *
 * <p>Pure: every value it needs — the deployment's currency, the look-back, today's date — arrives
 * as a parameter. No clock and no repository, so a test can put the boundary anywhere it likes
 * without moving the container's clock.
 *
 * <p><b>Why this refuses rather than coerces.</b> A date outside the look-back could be clamped, a
 * foreign currency could be converted, an eight-digit tail could be truncated to four. Each of
 * those turns a device defect into a silently wrong figure on a customer's screen, and the last one
 * would defeat the whole point of {@code ck_bi_account_masked_tail}. A rejected message is visible
 * in the per-message result set and the device can be fixed; a coerced one is not.
 */
public final class PayloadNormaliser {

  private PayloadNormaliser() {}

  /** Three or four digits, matching the CHECK constraint the column carries (V1.14.0). */
  private static final Pattern MASKED_TAIL = Pattern.compile("^[0-9]{3,4}$");

  /** Bank codes as agreed with MoiPay-Backend: short, upper-case, no separators. */
  private static final Pattern BANK_CODE = Pattern.compile("^[A-Z0-9]{2,16}$");

  /**
   * A single opaque token. Deliberately narrow: an idempotency key with whitespace in it could
   * reach {@link OtpGuard}'s sentence rule, and a key long enough to overflow {@code varchar(128)}
   * would fail at the database rather than here.
   */
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9:_.\\-]{16,120}$");

  /** Message type as used to select a parser template. Upper snake case, nothing else. */
  private static final Pattern MESSAGE_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,30}$");

  public static String requireBankCode(String raw) {
    String value = upper(raw);
    if (value == null || !BANK_CODE.matcher(value).matches()) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "The bank code on this message is not usable.");
    }
    return value;
  }

  public static String requireMessageType(String raw) {
    String value = upper(raw);
    if (value == null || !MESSAGE_TYPE.matcher(value).matches()) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "The message type on this message is not usable.");
    }
    return value;
  }

  public static String requireIdempotencyKey(String raw) {
    String value = trimToNull(raw);
    if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "This message has no usable idempotency key.");
    }
    return value;
  }

  /**
   * The masked tail, or null where the message carried none.
   *
   * <p>Three or four digits, or nothing. Anything else is a device that is not masking, and the correct
   * response to a possible unmasked instrument number is to refuse the message, not to shorten it.
   */
  public static String maskedTail(String raw) {
    String value = trimToNull(raw);
    if (value == null) {
      return null;
    }
    if (!MASKED_TAIL.matcher(value).matches()) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID,
          "The account tail on this message is not in the masked form.");
    }
    return value;
  }

  public static InstrumentType requireInstrumentType(String raw) {
    String value = upper(raw);
    if (value == null) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "This message has no instrument type.");
    }
    try {
      return InstrumentType.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "The instrument type must be CARD or BANK.");
    }
  }

  /** Absent direction reads as DEBIT, the same default the manual-entry path uses. */
  public static TxnDirection direction(String raw) {
    String value = upper(raw);
    if (value == null) {
      return TxnDirection.DEBIT;
    }
    try {
      return TxnDirection.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "The direction must be DEBIT or CREDIT.");
    }
  }

  /**
   * The deployment's currency, or a refusal.
   *
   * <p>Budget Insights adds one total in one currency. A message in another currency cannot be
   * added to it, and converting at an unstated rate would put a number on the screen that the
   * customer cannot reconcile against their own bank. Same position as the budget upsert.
   */
  public static String requireCurrency(String raw, String configured) {
    String value = upper(raw);
    if (value == null) {
      return configured;
    }
    if (!value.equals(configured)) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "Budget Insights works in " + configured + ".");
    }
    return configured;
  }

  /** Two decimal places, never negative — the shape {@code ck_bi_txn_amount} will accept. */
  public static BigDecimal requireAmount(BigDecimal raw) {
    if (raw == null || raw.signum() < 0) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "The amount on this message is not usable.");
    }
    return raw.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Rejects a date in the future or older than the approved look-back.
   *
   * <p>Identical rule to the manual-entry path, and identical reason: "spent so far this month" has
   * to mean what it says. The look-back refusal carries its own code because the device is expected
   * to see it — a phone that has been offline for a year will hold messages that are simply too old
   * to analyse, and it needs to tell them apart from messages it should retry.
   */
  public static LocalDate requireUsableDate(LocalDate date, LocalDate today, int lookbackMonths) {
    if (date == null || date.isAfter(today)) {
      // Not INSIGHTS_BUDGET_INVALID_PERIOD, which the manual-entry path uses: that code's message
      // asks the customer to choose a valid budget period, and the caller here is a handset with
      // no budget period to choose.
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID, "That message is dated in the future.");
    }
    if (date.isBefore(today.minusMonths(lookbackMonths).withDayOfMonth(1))) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED);
    }
    return date;
  }

  /** Merchant as the device parsed it, trimmed, or null. Never widened, never guessed. */
  public static String merchant(String raw) {
    return trimToNull(raw);
  }

  private static String upper(String raw) {
    String value = trimToNull(raw);
    return value == null ? null : value.toUpperCase(Locale.ROOT);
  }

  private static String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    return value.isEmpty() ? null : value;
  }
}
