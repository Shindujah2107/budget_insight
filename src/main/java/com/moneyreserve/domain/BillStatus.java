package com.moneyreserve.domain;

/** Where a customer's bill came from and what they said about it. */
public enum BillStatus {
  /**
   * Found in their payments and suggested (V1.27.0): not a bill until the customer tracks it. No
   * reminders, no questions, not in the list or total. Dropped if it stops looking recurring.
   */
  SUGGESTED,
  /** Found in their payments; not yet confirmed. Dropped again if it stops looking recurring. */
  DETECTED,
  /** Found and confirmed (or edited) by the customer: kept even when a payment is late. */
  CONFIRMED,
  /** "Not a bill": never suggested again for this payee. */
  DISMISSED,
  /** Added by the customer by hand. */
  MANUAL
}
