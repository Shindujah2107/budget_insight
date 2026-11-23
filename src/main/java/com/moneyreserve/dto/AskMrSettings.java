package com.moneyreserve.dto;

import java.util.List;

/**
 * Ask MR (Premium Insights, V1.29.0) settings, part of the budget policy so they are drafted,
 * approved, published, audited and cached the same way (Admin › Spending › Budget policy).
 *
 * @param enabled Ask MR is available at all (default OFF); it never grants Premium
 * @param freeTeaser customers without Premium see each answer's first part ("what happened")
 * @param weeklySummary the Sunday "How was my week?" summary is offered
 * @param worthKnowingPerWeek most "Worth knowing" cards one customer is shown in 7 days (0 = none)
 * @param minChangeAmount a change counts only from this many currency units...
 * @param minChangePct ...and this percentage of the earlier period
 * @param earlyMonthDay before this day of the month, month comparisons say "too early"
 * @param oneOffTenths a single payment of at least this many tenths of the category's usual (median)
 *     payment is called a one-off (20 = 2×)
 */
public record AskMrSettings(
    boolean enabled,
    boolean freeTeaser,
    boolean weeklySummary,
    int worthKnowingPerWeek,
    int minChangeAmount,
    int minChangePct,
    int earlyMonthDay,
    int oneOffTenths) {

  public static final AskMrSettings DEFAULTS = new AskMrSettings(false, true, true, 2, 1000, 15, 5, 20);

  /** One problem per invalid field, as {@code field: reason}. */
  public void problems(List<String[]> out) {
    range(out, "askMr.worthKnowingPerWeek", worthKnowingPerWeek, 0, 7);
    range(out, "askMr.minChangeAmount", minChangeAmount, 0, 1_000_000);
    range(out, "askMr.minChangePct", minChangePct, 1, 100);
    range(out, "askMr.earlyMonthDay", earlyMonthDay, 1, 15);
    range(out, "askMr.oneOffTenths", oneOffTenths, 12, 50);
  }

  private static void range(List<String[]> out, String field, int v, int min, int max) {
    if (v < min || v > max) {
      out.add(new String[] {field, "must be between " + min + " and " + max});
    }
  }
}
