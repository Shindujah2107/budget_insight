package com.moipay.budgetinsights.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A merchant rule as the Admin saves it (M4 create, M5 edit).
 *
 * <p>{@code SIMPLE}: {@code matchType}, {@code names}, {@code categoryId}, {@code priorityLevel},
 * {@code active}; the server generates the stored pattern, type and priority number.
 * {@code ADVANCED}: {@code matchType}, {@code pattern}, {@code categoryId}, {@code priority},
 * {@code active}; stored as given after the pattern checks, and the rule loses its simple form.
 */
public record AdminMerchantRuleInput(
    @NotNull @Pattern(regexp = "^(SIMPLE|ADVANCED)$", message = "mode must be SIMPLE or ADVANCED")
        String mode,
    @NotNull
        @Pattern(regexp = "^(EXACT|PREFIX|REGEX|TERMS)$", message = "matchType must be EXACT, PREFIX, REGEX or TERMS")
        String matchType,
    @Size(max = 60, message = "at most 60 names") List<String> names,
    @Size(max = 1000, message = "pattern may be at most 1000 characters") String pattern,
    @NotNull UUID categoryId,
    @Pattern(regexp = "^(HIGH|NORMAL|LOW)$", message = "priorityLevel must be HIGH, NORMAL or LOW")
        String priorityLevel,
    @Min(value = 1, message = "priority must be 1-999") @Max(value = 999, message = "priority must be 1-999")
        Integer priority,
    @NotNull Boolean active) {}
