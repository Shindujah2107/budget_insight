package com.moneyreserve.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A change to a bank: a new display name, turning it on or off, or both. Omitted fields stay as
 * they are. The code itself never changes.
 */
public record AdminBankUpdate(
    @Size(min = 2, max = 64, message = "bankName must be 2-64 characters")
        @Pattern(
            regexp = "^[\\p{L}0-9 .,&'()\\-]+$",
            message = "bankName may contain letters, digits, spaces and . , & ' ( ) -")
        String bankName,
    Boolean active) {}
