package com.moipay.budgetinsights.dto;

import java.util.List;
import java.util.Map;

/**
 * The checks a template passes or fails (T4 generate, T5 validate): portable pattern, field map,
 * samples matched, regression against the stored redacted samples. Extracted values and highlight
 * offsets come back; the samples themselves never do.
 */
public record AdminTemplateCheck(
    List<AdminValidation.Check> checks,
    List<AdminValidation.Issue> warnings,
    List<Extracted> extracted) {

  /**
   * @param sample index of the sample in the request (0 = the main sample)
   * @param fields extracted values: amount, currency, direction, merchant, txnDate, txnTime,
   *     maskedTail
   * @param highlights where each captured field sits in the sample (character offsets)
   * @param category the category the extracted merchant would get today
   */
  public record Extracted(
      int sample,
      boolean matched,
      Map<String, String> fields,
      List<Highlight> highlights,
      AdminMerchantRuleTest.CategoryRef category) {}

  public record Highlight(String field, int start, int end) {}
}
