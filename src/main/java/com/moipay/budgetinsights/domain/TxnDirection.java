package com.moipay.budgetinsights.domain;

/**
 * Money out or money in. Spend figures count DEBIT only; the cashflow view uses both. The amount
 * column is always positive — the sign comes from here.
 */
public enum TxnDirection {
  DEBIT,
  CREDIT
}
