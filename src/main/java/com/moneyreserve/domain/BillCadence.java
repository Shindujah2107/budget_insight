package com.moneyreserve.domain;

/**
 * How often a bill comes: the typical gap between payments, in days. A gap of about twice this
 * (one skipped period) still counts as the same cadence.
 */
public enum BillCadence {
  WEEKLY(6, 8),
  MONTHLY(26, 35),
  QUARTERLY(84, 98),
  YEARLY(350, 380);

  private final int minDays;
  private final int maxDays;

  BillCadence(int minDays, int maxDays) {
    this.minDays = minDays;
    this.maxDays = maxDays;
  }

  public int minDays() {
    return minDays;
  }

  public int maxDays() {
    return maxDays;
  }

  /** True for a single gap of this cadence. */
  public boolean fits(long days) {
    return days >= minDays && days <= maxDays;
  }

  /** True for one skipped period: about twice the gap. */
  public boolean fitsSkipped(long days) {
    return days >= 2L * minDays && days <= 2L * maxDays;
  }
}
