package com.moneyreserve.domain;

/**
 * Merchant map matching strategy.
 *
 * <p>{@code TERMS} is the generic keyword engine ({@link
 * com.moneyreserve.service.calc.MerchantTerms}): whole words, phrases, word stems, exact
 * names and exclusions, matched against the cleaned merchant name. The other three are the
 * original strategies and keep their exact behaviour.
 */
public enum MatchType {
  EXACT,
  PREFIX,
  REGEX,
  TERMS
}
