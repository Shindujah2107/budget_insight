package com.moipay.budgetinsights.domain;

/**
 * Merchant map matching strategy.
 *
 * <p>{@code TERMS} is the generic keyword engine ({@link
 * com.moipay.budgetinsights.service.calc.MerchantTerms}): whole words, phrases, word stems, exact
 * names and exclusions, matched against the cleaned merchant name. The other three are the
 * original strategies and keep their exact behaviour.
 */
public enum MatchType {
  EXACT,
  PREFIX,
  REGEX,
  TERMS
}
