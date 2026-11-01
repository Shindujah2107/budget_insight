package com.moneyreserve.domain;

/**
 * Why a transaction has the category it has.
 *
 * <ul>
 *   <li>{@code RULE}: a merchant rule or the message type decided it, including a deliberate
 *       "Other" (an ATM receipt, a rule that maps to Other).
 *   <li>{@code USER}: the customer chose it, on this row or with "always use this category" for
 *       the merchant.
 *   <li>{@code FALLBACK}: nothing matched, so it was filed under Other. This is what the app shows
 *       as "needs a category"; a deliberate Other is not.
 * </ul>
 *
 * <p>Null only on rows stored before V1.17.0 that the startup backfill has not reached yet.
 */
public enum CategorySource {
  RULE,
  USER,
  FALLBACK
}
