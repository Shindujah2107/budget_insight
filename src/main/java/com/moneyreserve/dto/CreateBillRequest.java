package com.moneyreserve.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A bill the customer adds by hand. */
public record CreateBillRequest(
    @NotBlank @Size(max = 64) String name,
    @NotNull @DecimalMin("0.01") BigDecimal expectedAmount,
    @NotNull @Pattern(regexp = "^(WEEKLY|MONTHLY|QUARTERLY|YEARLY)$") String cadence,
    @NotNull LocalDate nextDueOn,
    @Min(0) @Max(14) Short remindDaysBefore) {}
