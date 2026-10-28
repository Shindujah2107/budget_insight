package com.moneyreserve.domain;

/** What the customer did to a transaction. Recorded append-only. */
public enum AuditAction {
  CREATE,
  EDIT,
  RECATEGORISE,
  DELETE
}
