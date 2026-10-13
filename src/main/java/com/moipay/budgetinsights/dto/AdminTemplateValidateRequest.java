package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Advanced-mode check of a raw pattern and field map (T5). Optional redacted samples are matched
 * and thrown away: never stored, logged or echoed back.
 *
 * @param templateId the template being edited, so the regression check replaces it
 */
public record AdminTemplateValidateRequest(
    @NotBlank @Size(max = 4000, message = "pattern may be at most 4,000 characters") String pattern,
    @NotNull JsonNode fieldMap,
    @NotBlank @Size(max = 16) String bankCode,
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "messageType must be UPPER_SNAKE, 2-32 characters")
        String messageType,
    @Size(max = 5, message = "at most 5 samples") List<@Size(max = 20000, message = "sample is far too long") String> samples,
    UUID templateId) {}
