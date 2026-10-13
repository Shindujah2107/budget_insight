package com.moipay.budgetinsights.domain;

/** What was done in response. Never carries message content or a financial value. */
public enum ComplianceEventAction {
  DISCARDED,
  REJECTED,
  THROTTLED,
  RECORDED
}
