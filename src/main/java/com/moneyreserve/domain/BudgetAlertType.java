package com.moneyreserve.domain;

/**
 * What a raised alert is about.
 *
 * <p>Deliberately not the same vocabulary as the summary's {@code state} field, which answers "how
 * does this category look right now" and is recomputed on every read. These answer "what were we
 * told, and when", and are written once.
 */
public enum BudgetAlertType {

  /** A category has reached its configured share of its cap — 80% by default. */
  CATEGORY_APPROACHING,

  /** A category has reached or passed its cap. */
  CATEGORY_EXCEEDED,

  /** Spend for the period has reached or passed the overall monthly target. */
  BUDGET_EXCEEDED,

  /** A recurring bill is due within the customer's reminder days. Once per bill per due date. */
  BILL_DUE,

  /** A bill's latest payment is 30% or more above the average of the three before it. */
  BILL_JUMPED,

  /** An expected bill has not appeared five days after its due date. */
  BILL_MISSED
}
