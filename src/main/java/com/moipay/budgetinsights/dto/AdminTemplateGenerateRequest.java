package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Template wizard, highlight step (T4): the sample, up to four more of the same format, and the
 * highlights the Admin confirmed. Generates pattern, field map and checks; saves nothing. The
 * samples are never stored, logged or returned.
 */
public record AdminTemplateGenerateRequest(
    @NotBlank @Size(max = 16) String bankCode,
    @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "messageType must be UPPER_SNAKE, 2-32 characters")
        String messageType,
    @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,47}$", message = "logicalType must be UPPER_SNAKE")
        String logicalType,
    @NotBlank @Pattern(regexp = "^(BANK|CARD)$", message = "instrumentType must be BANK or CARD") String instrumentType,
    @Size(max = 10) List<@NotBlank @Size(max = 32) String> senders,
    @NotBlank @Size(max = 20000, message = "sample is far too long") String sample,
    @Size(max = 4, message = "at most 4 extra samples") List<@Size(max = 20000, message = "sample is far too long") String> extraSamples,
    @NotNull @Size(max = 40) List<@Valid Annotation> annotations,
    JsonNode fixed,
    JsonNode options,
    UUID templateId,
    Boolean redactionConfirmed) {

  public record Annotation(
      @NotNull @Min(0) @Max(1000) Integer start,
      @NotNull @Min(0) @Max(1000) Integer end,
      @NotNull
          @Pattern(
              regexp = "^(AMOUNT|CURRENCY|DIRECTION|MERCHANT|DATE|TIME|TAIL|IGNORE)$",
              message = "field must be AMOUNT, CURRENCY, DIRECTION, MERCHANT, DATE, TIME, TAIL or IGNORE")
          String field,
      @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "direction must be DEBIT or CREDIT") String direction,
      @Size(max = 3) List<@Size(max = 30) String> counterpartWords,
      @Size(max = 16) String format,
      @Size(max = 16) String ignoreKind) {}
}
