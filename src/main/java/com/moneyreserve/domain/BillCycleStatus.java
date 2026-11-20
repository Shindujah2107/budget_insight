package com.moneyreserve.domain;

/** Where one due date of a bill stands (V1.26.0). */
public enum BillCycleStatus {
  /** Waiting: asked about from the due date for a few days. */
  DUE,
  /** Settled once, by one payment. */
  PAID,
  /** No answer within the asking days: no more reminders, still answerable later. */
  NOT_CONFIRMED,
  /** The customer said they did not pay it. */
  SKIPPED;

  /** Still open to an answer. */
  public boolean open() {
    return this == DUE || this == NOT_CONFIRMED;
  }
}
