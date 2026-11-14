package com.moneyreserve.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Changes to one bill; absent fields are left alone. {@code name} blank goes back to the detected
 * name. {@code confirm} keeps a detected bill; {@code dismiss} marks it "not a bill" (a bill added
 * by hand is removed). Amount, cadence and next date apply to bills added by hand; a detected bill
 * takes them from its payments.
 */
public record UpdateBillRequest(
    @Size(max = 64) String name,
    Boolean confirm,
    Boolean dismiss,
    @Min(0) @Max(14) Short remindDaysBefore,
    @DecimalMin("0.01") BigDecimal expectedAmount,
    @Pattern(regexp = "^(WEEKLY|MONTHLY|QUARTERLY|YEARLY)$") String cadence,
    LocalDate nextDueOn,
    /** Change the bill's category (V1.24.0). */
    java.util.UUID categoryId) {}
