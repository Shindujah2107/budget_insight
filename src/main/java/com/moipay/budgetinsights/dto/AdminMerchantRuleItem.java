package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moipay.budgetinsights.domain.ConfigItemState;
import java.util.List;
import java.util.UUID;

/**
 * A merchant rule in the Admin's words (M1 list row, M2 detail).
 *
 * <p>The list never carries the stored pattern; {@code generated} is filled only on the detail read,
 * for the Advanced settings panel.
 *
 * @param names the merchant names, empty when {@code advancedOnly}
 * @param matchType what the Admin sees: EXACT, PREFIX or REGEX (contains)
 * @param advancedOnly the stored pattern is not one the simple form can express
 * @param evaluationOrder 1-based position in which the categoriser tries this rule (active rules)
 */
public record AdminMerchantRuleItem(
    UUID id,
    ConfigItemState state,
    UUID pendingChangeId,
    List<String> names,
    String matchType,
    UUID categoryId,
    String priorityLevel,
    boolean active,
    boolean advancedOnly,
    Integer evaluationOrder,
    Usage usage,
    @JsonInclude(JsonInclude.Include.NON_NULL) Generated generated) {

  public record Usage(long matches30d) {}

  /**
   * What is stored (Advanced settings).
   *
   * @param priorityBand the number range of {@code priorityLevel}
   */
  public record Generated(
      String matchType, String pattern, int priority, String priorityBand, AdminMerchantRuleSpec ruleSpec) {}
}
