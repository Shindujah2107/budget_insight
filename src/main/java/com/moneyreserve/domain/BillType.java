package com.moneyreserve.domain;

/** What kind of bill a biller sends, which sets how much its amount may vary between payments. */
public enum BillType {
  ELECTRICITY(40),
  WATER(40),
  TELECOM(25),
  STREAMING(2),
  INSURANCE(5),
  LOAN(5),
  CARD(100),
  RENT(5),
  OTHER(25);

  private final int defaultTolerancePct;

  BillType(int defaultTolerancePct) {
    this.defaultTolerancePct = defaultTolerancePct;
  }

  /** How far (±%) one payment may stray from the usual amount and still be the same bill. */
  public int defaultTolerancePct() {
    return defaultTolerancePct;
  }
}
