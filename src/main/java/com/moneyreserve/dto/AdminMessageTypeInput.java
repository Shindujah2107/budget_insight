package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A message type as a Super Admin adds it (POST) or changes it (PUT). On PUT {@code code} is ignored
 * (it never changes) and every other field is replaced; {@code fixedCategoryCode} null or blank
 * means "decided by merchant rules".
 */
public record AdminMessageTypeInput(
    @Pattern(regexp = "^\\s*[A-Z][A-Z0-9_]{1,31}\\s*$", message = "code must be UPPER_SNAKE, 2-32 characters")
        String code,
    @NotBlank(message = "displayName is required")
        @Size(min = 2, max = 64, message = "displayName must be 2-64 characters")
        String displayName,
    @NotBlank(message = "logicalType is required")
        @Pattern(regexp = "^[A-Z][A-Z_]{1,47}$", message = "logicalType must be one of the listed groups")
        String logicalType,
    @Pattern(regexp = "^(FROM_SMS|DEBIT|CREDIT)$", message = "defaultDirection must be FROM_SMS, DEBIT or CREDIT")
        String defaultDirection,
    @Pattern(regexp = "^(BANK|CARD)$", message = "defaultInstrument must be BANK or CARD")
        String defaultInstrument,
    @Size(max = 32, message = "fixedCategoryCode is at most 32 characters") String fixedCategoryCode,
    Boolean active) {}
