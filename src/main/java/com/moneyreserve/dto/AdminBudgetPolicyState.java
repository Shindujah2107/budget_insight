package com.moneyreserve.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * The budget policy every customer's budget, alerts and 50/30/20 recommendations use (a published
 * row or a draft's desired state). Edited in Admin › Configuration › Budget Policy.
 *
 * @param needsPct share of the monthly target for Needs (50/30/20)
 * @param trailingMonths complete months before the viewed one that the suggested monthly target and
 *     the recommendations take their median over
 * @param defaultAlertPct "approaching limit" threshold for category limits whose customer did not
 *     choose one
 * @param paceTolerancePct how far average daily spend may run above the ideal before the status is
 *     AHEAD_OF_PACE
 * @param alertCategoryApproaching whether CATEGORY_APPROACHING alerts are raised
 * @param alertCategoryExceeded whether CATEGORY_EXCEEDED alerts are raised
 * @param alertBudgetExceeded whether BUDGET_EXCEEDED alerts are raised
 */
public record AdminBudgetPolicyState(
    int needsPct,
    int wantsPct,
    int savingsPct,
    int trailingMonths,
    int defaultAlertPct,
    int paceTolerancePct,
    boolean alertCategoryApproaching,
    boolean alertCategoryExceeded,
    boolean alertBudgetExceeded) {

  public static final int MIN_SPLIT_PCT = 0;
  public static final int MAX_SPLIT_PCT = 100;
  public static final int MIN_TRAILING_MONTHS = 1;
  public static final int MAX_TRAILING_MONTHS = 12;
  public static final int MIN_ALERT_PCT = 50;
  public static final int MAX_ALERT_PCT = 95;
  public static final int MIN_PACE_PCT = 0;
  public static final int MAX_PACE_PCT = 25;

  /** One problem per invalid field, as {@code field: reason}; empty when the policy is valid. */
  public List<String[]> problems() {
    List<String[]> out = new ArrayList<>();
    range(out, "needsPct", needsPct, MIN_SPLIT_PCT, MAX_SPLIT_PCT);
    range(out, "wantsPct", wantsPct, MIN_SPLIT_PCT, MAX_SPLIT_PCT);
    range(out, "savingsPct", savingsPct, MIN_SPLIT_PCT, MAX_SPLIT_PCT);
    if (needsPct + wantsPct + savingsPct != 100) {
      out.add(
          new String[] {
            "savingsPct",
            "Needs + Wants + Savings must add up to 100 (now " + (needsPct + wantsPct + savingsPct) + ")"
          });
    }
    range(out, "trailingMonths", trailingMonths, MIN_TRAILING_MONTHS, MAX_TRAILING_MONTHS);
    range(out, "defaultAlertPct", defaultAlertPct, MIN_ALERT_PCT, MAX_ALERT_PCT);
    range(out, "paceTolerancePct", paceTolerancePct, MIN_PACE_PCT, MAX_PACE_PCT);
    return out;
  }

  /** One problem as a sentence for messages: "Needs + Wants + ..." or "defaultAlertPct must be ...". */
  public static String describe(String[] problem) {
    return problem[1].startsWith("Needs") ? problem[1] : problem[0] + " " + problem[1];
  }

  /** True when no budget alert of any type would be raised. */
  public boolean allAlertsOff() {
    return !alertCategoryApproaching && !alertCategoryExceeded && !alertBudgetExceeded;
  }

  private static void range(List<String[]> out, String field, int v, int min, int max) {
    if (v < min || v > max) {
      out.add(new String[] {field, "must be between " + min + " and " + max});
    }
  }
}
