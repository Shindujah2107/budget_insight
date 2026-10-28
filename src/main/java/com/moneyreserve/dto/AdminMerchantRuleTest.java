package com.moneyreserve.dto;

import java.util.List;
import java.util.UUID;

/**
 * Result of "Test this rule" (M3). Counts and configuration only: no transaction, customer or
 * amount is returned, and the tested description is not echoed.
 *
 * @param category the category the description would get with this rule saved
 * @param decidedBy the rule that decided it; {@code null} when no rule matched (the OTHER fallback)
 * @param thisRuleMatches whether this rule matches the description at all
 * @param suggestion set when this rule matches but another rule wins, and a priority level would
 *     let it win
 * @param impact debit transactions of the last 30 days, from merchants seen for at least k
 *     customers, that this rule matches; {@code null} when the count timed out
 */
public record AdminMerchantRuleTest(
    CategoryRef category,
    DecidedBy decidedBy,
    boolean thisRuleMatches,
    Suggestion suggestion,
    Impact impact,
    String impactUnavailableReason,
    List<String> warnings,
    AdminMerchantRuleItem.Generated generated) {

  public record CategoryRef(String code, String name) {}

  /**
   * @param matchedText the part of the (normalised) description the rule matched
   */
  public record DecidedBy(
      boolean thisRule,
      UUID ruleId,
      List<String> names,
      String matchType,
      String priorityLevel,
      String matchedText) {}

  public record Suggestion(String priorityLevel, String resultCategory) {}

  public record Impact(String window, long wouldMatch, long inOtherToday, int minCustomers) {}
}
