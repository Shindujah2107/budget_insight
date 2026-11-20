package com.moneyreserve.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "I paid this bill" (V1.23.0), from the due-date reminder or a confirmed payment attempt.
 * {@code paidOn} defaults to today and {@code amount} to the bill's expected amount;
 * {@code categoryId} to the bill's category. {@code originBankCode} is set when the payment was a
 * confirmed attempt from that bank. {@code clientRef} makes a retry a duplicate.
 *
 * <p>Which cycle (V1.26.0): {@code cycleId} answers one cycle from the bill's history (one that
 * was never confirmed); {@code dueOn} is the due date the customer was shown, so a second phone or
 * a stale screen gets "already paid" instead of paying the next cycle. Neither: the current one.
 */
public record MarkBillPaidRequest(
    @NotBlank @Size(max = 96) String clientRef,
    LocalDate paidOn,
    @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    UUID categoryId,
    @Pattern(regexp = "^[A-Z0-9]{2,16}$") String originBankCode,
    LocalDate dueOn,
    UUID cycleId) {

  /** The fields before V1.26.0 (the current cycle). */
  public MarkBillPaidRequest(
      String clientRef, LocalDate paidOn, BigDecimal amount, UUID categoryId, String originBankCode) {
    this(clientRef, paidOn, amount, categoryId, originBankCode, null, null);
  }
}
