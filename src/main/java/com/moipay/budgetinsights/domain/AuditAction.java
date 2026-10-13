package com.moipay.budgetinsights.domain;

/** What the customer did to a transaction. Recorded append-only. */
public enum AuditAction {
  CREATE,
  EDIT,
  RECATEGORISE,
  DELETE
}
