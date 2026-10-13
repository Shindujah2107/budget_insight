package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A template as the Admin saves it (T6 create, T7 edit).
 *
 * <p>{@code BUILDER}: {@code builderSpec} only; the server regenerates pattern and field map from
 * it, so a client cannot store a pattern that differs from the preview. {@code ADVANCED}: {@code
 * pattern} and {@code fieldMap}, stored after the portable-pattern and field-map checks; the
 * template loses its wizard spec. No sample text is ever sent on save.
 */
public record AdminTemplateInput(
    @NotNull @Pattern(regexp = "^(BUILDER|ADVANCED)$", message = "mode must be BUILDER or ADVANCED")
        String mode,
    @NotBlank @Size(max = 16) String bankCode,
    @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "messageType must be UPPER_SNAKE, 2-32 characters")
        String messageType,
    @Size(max = 4000, message = "pattern may be at most 4,000 characters") String pattern,
    JsonNode fieldMap,
    JsonNode builderSpec,
    @NotNull Boolean active) {}
