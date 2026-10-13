package com.moipay.budgetinsights.dto;

import java.util.UUID;

/** A merchant rule as stored in {@code bi_merchant_category_map}, plus its form input. */
public record AdminMerchantRuleState(
    String matchType,
    String pattern,
    UUID categoryId,
    int priority,
    boolean active,
    AdminMerchantRuleSpec ruleSpec) {

  public AdminMerchantRuleState withActive(boolean value) {
    return new AdminMerchantRuleState(matchType, pattern, categoryId, priority, value, ruleSpec);
  }

  public AdminMerchantRuleState withCategory(UUID value) {
    return new AdminMerchantRuleState(matchType, pattern, value, priority, active, ruleSpec);
  }
}
