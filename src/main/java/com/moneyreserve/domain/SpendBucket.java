package com.moneyreserve.domain;

/**
 * The three halves of 50/30/20, which is of course three thirds.
 *
 * <p>Which category falls in which bucket is a business rule, not a property of the category, so
 * the mapping lives in configuration rather than here. A category in none of the three lists is
 * unbucketed and is reported separately — {@code OTHER} is the obvious one, and forcing the
 * fallback category into a bucket would distort every percentage on the panel.
 */
public enum SpendBucket {
  NEEDS,
  WANTS,
  SAVINGS
}
