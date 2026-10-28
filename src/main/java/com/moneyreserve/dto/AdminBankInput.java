package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new bank. {@code bankCode} is the 4-digit CBSL code — the code the
 * banks' SMS use — and cannot be changed later. Budget Insights reads bank SMS only.
 */
public record AdminBankInput(
    @NotBlank(message = "bankCode is required")
        @Pattern(regexp = "^\\s*[0-9]{4}\\s*$", message = "bankCode must be the 4-digit CBSL bank code")
        String bankCode,
    @NotBlank(message = "bankName is required")
        @Size(min = 2, max = 64, message = "bankName must be 2-64 characters")
        @Pattern(
            regexp = "^[\\p{L}0-9 .,&'()\\-]+$",
            message = "bankName may contain letters, digits, spaces and . , & ' ( ) -")
        String bankName) {}
