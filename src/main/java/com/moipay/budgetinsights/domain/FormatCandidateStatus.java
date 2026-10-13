package com.moipay.budgetinsights.domain;

/** Where a discovered SMS format stands. */
public enum FormatCandidateStatus {
  /** Waiting for a template (shown to admins once enough users have sent it). */
  OPEN,
  /** A live template now reads it. */
  RESOLVED,
  /** An admin decided it is not something to template (a promotion, not a transaction…). */
  REJECTED
}
