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
 */
public record MarkBillPaidRequest(
    @NotBlank @Size(max = 96) String clientRef,
    LocalDate paidOn,
    @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
    UUID categoryId,
    @Pattern(regexp = "^[A-Z0-9]{2,16}$") String originBankCode) {}
