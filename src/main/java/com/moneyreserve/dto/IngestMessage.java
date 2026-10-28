package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One bank message, already parsed on the device (BI-08).
 *
 * <p><b>There is no raw message field here, and there must never be one.</b> That absence is the
 * third of the three OTP controls and the only one that cannot fail at runtime: if the contract has
 * no field capable of carrying message text, then a device filter that misses an OTP and a server
 * guard that misses it too still cannot put the code in this service's database, because there is
 * nowhere to put it. The architecture test (BI-33) asserts this, so adding {@code rawMessage},
 * {@code body} or {@code text} to this class fails the build rather than the review.
 *
 * <p>The one lawful route for raw text is the separate, consent-gated parser-improvement
 * submission, which writes encrypted to {@code bi_raw_message} with a TTL and is never read to
 * build an insight.
 *
 * <p>{@code merchant} is the only field here with any freedom in it, and it is the field
 * {@link com.moneyreserve.service.ingest.OtpGuard} works hardest on.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestMessage {

  /**
   * Computed on the device over sender, timestamp, amount and tail.
   *
   * <p>This is what makes replaying a queue harmless. It is echoed back in the result set exactly
   * as sent, so the device can match a result to the message still sitting in its outbox.
   */
  @NotBlank(message = "Each message needs an idempotency key.")
  @Size(max = 120, message = "The idempotency key is too long.")
  private String idempotencyKey;

  @NotBlank(message = "Each message needs a bank code.")
  @Size(max = 16, message = "The bank code is too long.")
  private String bankCode;

  /** Selects the parser template, and decides whether this kind of message is in scope at all. */
  @NotBlank(message = "Each message needs a message type.")
  @Size(max = 32, message = "The message type is too long.")
  private String messageType;

  /** When the device parsed it. Recorded for support, never used as the transaction date. */
  private OffsetDateTime parsedAt;

  @NotNull(message = "Each message needs an amount.")
  @DecimalMin(value = "0.00", message = "The amount cannot be negative.")
  @Digits(integer = 17, fraction = 2, message = "The amount is not a valid figure.")
  private BigDecimal amount;

  @Pattern(regexp = "^[A-Z]{3}$", message = "Please provide a three-letter currency code.")
  private String currency;

  @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "Direction must be DEBIT or CREDIT.")
  private String direction;

  @Size(max = 128, message = "The merchant name is too long.")
  private String merchant;

  @NotNull(message = "Each message needs a transaction date.")
  private LocalDate txnDate;

  @NotBlank(message = "Each message needs an instrument type.")
  @Pattern(regexp = "^(CARD|BANK)$", message = "The instrument type must be CARD or BANK.")
  private String instrumentType;

  /**
   * Last three or four digits of the instrument, or absent. Some banks show only three digits
   * (e.g. BOC "A/C No XXXXXXXXXX463").
   *
   * <p>Four digits is the most this service will accept — see
   * {@code ck_bi_account_masked_tail}. Whether even this may be stored for a card is held behind
   * {@code masked-card-to-backend-enabled}; with that off the tail is dropped on arrival and the
   * transaction is recorded against no account.
   */
  @Pattern(regexp = "^[0-9]{3,4}$", message = "The account tail must be three or four digits.")
  private String maskedTail;

  /** The template that produced these fields. Null where the on-device fallback parsed it. */
  private UUID parserTemplateId;

  /** The device's own confidence, 0 to 1. Recorded in the log line, not in the ledger. */
  private BigDecimal parseConfidence;
}
