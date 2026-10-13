package com.moipay.budgetinsights.domain;

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
  BUDGET_EXCEEDED
}
