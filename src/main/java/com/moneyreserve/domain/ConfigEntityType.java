package com.moneyreserve.domain;

/** The reference data the admin can change. Declaration order is the publish apply order. */
public enum ConfigEntityType {
  CATEGORY("Spending category"),
  MERCHANT_RULE("Merchant rule"),
  SENDER("Bank SMS sender"),
  TEMPLATE("SMS template"),
  /** A bank in the registry: rename, turn on/off, delete (adding a bank stays with its sender). */
  BANK("Bank"),
  /** The single budget policy row (bi_budget_policy). */
  BUDGET_POLICY("Budget policy"),
  /** A known biller for the bills radar (bi_biller, V1.19.0). */
  BILLER("Biller"),
  /** An Ask MR suggestion's wording (bi_ask_mr_tip, V1.29.0): edited and turned on/off, never added. */
  ASK_MR_TIP("Ask Mate tip");

  private final String label;

  ConfigEntityType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }
}
