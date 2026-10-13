package com.moipay.budgetinsights.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * "Test this rule" (M3): the rule as it stands in the form, and a transaction description to try.
 * The description is evaluated and thrown away: it is never stored, logged or echoed back.
 */
public record AdminMerchantRuleTestRequest(
    @NotNull @Valid Rule rule,
    @NotBlank @Size(max = 128, message = "description may be at most 128 characters") String description) {

  /**
   * @param ruleId the rule being edited, so the test replaces it instead of adding a second copy
   */
  public record Rule(
      @NotNull
          @Pattern(regexp = "^(EXACT|PREFIX|REGEX)$", message = "matchType must be EXACT, PREFIX or REGEX")
          String matchType,
      @Size(max = 60, message = "at most 60 names") List<String> names,
      @NotNull UUID categoryId,
      @NotNull
          @Pattern(regexp = "^(HIGH|NORMAL|LOW)$", message = "priorityLevel must be HIGH, NORMAL or LOW")
          String priorityLevel,
      UUID ruleId) {}
}
