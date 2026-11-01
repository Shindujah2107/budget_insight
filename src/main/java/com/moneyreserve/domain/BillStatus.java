package com.moneyreserve.domain;

/** Where a customer's bill came from and what they said about it. */
public enum BillStatus {
  /** Found in their payments; not yet confirmed. Dropped again if it stops looking recurring. */
  DETECTED,
  /** Found and confirmed (or edited) by the customer: kept even when a payment is late. */
  CONFIRMED,
  /** "Not a bill": never suggested again for this payee. */
  DISMISSED,
  /** Added by the customer by hand. */
  MANUAL
}
