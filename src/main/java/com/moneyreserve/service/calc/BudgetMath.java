package com.moneyreserve.service.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The budget arithmetic, with no repository, clock or entity anywhere in it.
 *
 * <p>Every figure on the budget ring is derived here rather than in the service, because each one
 * has a boundary a customer will find before a reviewer does: the day they set a budget, the last
 * day of the month, a month they have already lived through, a target they have gone past. Those
 * are cheap to pin down in a unit test and expensive to reason about inside a transaction.
 *
 * <p>Nothing here caps anything. A customer who has spent twice their target is at 200%, and the
 * screen is supposed to say so — clamping to 100 would turn the one state worth acting on into the
 * same picture as staying exactly on target.
 */
public final class BudgetMath {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private BudgetMath() {}

  /**
   * Days of the period the customer has lived through, today included.
   *
   * <p>Clamped to the period at both ends. A month already past is fully elapsed; a month not yet
   * begun has elapsed nothing. Without the clamp, asking for last month would report more days
   * elapsed than the month has, and asking for next month would report a negative count.
   */
  public static int daysElapsed(InsightsPeriod period, LocalDate today) {
    if (today.isBefore(period.start())) {
      return 0;
    }
    if (!today.isBefore(period.end())) {
      return period.days();
    }
    return (int) ChronoUnit.DAYS.between(period.start(), today) + 1;
  }

  /** Days of the period still to come, today excluded. Never negative. */
  public static int daysRemaining(InsightsPeriod period, LocalDate today) {
    return period.days() - daysElapsed(period, today);
  }

  /**
   * Spend as a percentage of the target, to one decimal place.
   *
   * <p>Deliberately uncapped — see the class note.
   *
   * <p>The target must be greater than zero, which the {@code ck_bi_budget_limit} check constraint
   * and the refusal in the upsert both guarantee. A zero target has no utilisation to report, so
   * this throws rather than inventing one: reaching it means a row exists that the database says
   * cannot, and that should fail loudly rather than put a made-up percentage on a customer's
   * screen.
   */
  public static BigDecimal utilisationPct(BigDecimal spent, BigDecimal limit) {
    return spent.multiply(HUNDRED).divide(limit, 1, RoundingMode.HALF_UP);
  }

  /**
   * An amount spread over a number of days.
   *
   * <p>A zero divisor is treated as one. That happens only on a period that has not started, where
   * the amount is zero anyway, so the alternative is an arithmetic exception on a request that is
   * perfectly legitimate.
   */
  public static BigDecimal perDay(BigDecimal amount, int days) {
    return amount.divide(BigDecimal.valueOf(Math.max(days, 1)), 2, RoundingMode.HALF_UP);
  }

  /**
   * Where the customer's daily rate sits against the rate that would exactly spend the target.
   *
   * <p>A tolerance band rather than a bare comparison. With the band at zero this is the strict
   * comparison the published contract was built against; above zero it stops a customer who is a
   * rupee a day over ideal from being told they are spending too fast.
   */
  public static String variance(BigDecimal average, BigDecimal ideal, int tolerancePct) {
    BigDecimal band =
        ideal.multiply(BigDecimal.valueOf(Math.max(tolerancePct, 0))).divide(HUNDRED, 4, RoundingMode.HALF_UP);
    if (average.compareTo(ideal.add(band)) > 0) {
      return "ABOVE";
    }
    if (average.compareTo(ideal.subtract(band)) < 0) {
      return "UNDER";
    }
    return "ON";
  }

  /**
   * The headline state.
   *
   * <p>Being over the target outranks pace. Somebody who has already spent the month's money is
   * over budget whatever their daily rate has settled to since.
   */
  /**
   * Whether a cap is worth an alert, and which one.
   *
   * <p>Spending exactly the cap counts as EXCEEDED, not APPROACHING. At that point there is nothing
   * left to approach: the next rupee is over, and telling a customer they are "approaching" a limit
   * they have used entirely would be worse than saying nothing.
   *
   * <p>Lives here rather than in a service because two callers need the same answer — the summary,
   * which recomputes it on every read, and the alert evaluation, which writes it down once. Two
   * copies of a threshold rule drift, and the drift shows up as a customer being told one thing by
   * the card and another by the alert.
   *
   * @param thresholdPct the cap's own threshold, or null to use the deployment default
   * @return the state, or null when the cap is not worth mentioning
   */
  public static CapState capState(
      BigDecimal spent, BigDecimal limit, Short thresholdPct, short defaultThresholdPct) {

    if (limit == null || limit.signum() <= 0 || spent == null) {
      return null;
    }
    if (spent.compareTo(limit) >= 0) {
      return CapState.EXCEEDED;
    }
    short pct = thresholdPct == null ? defaultThresholdPct : thresholdPct;
    // No guard on a zero or negative threshold, deliberately. R2 has shipped without one since
    // this rule was written, and there it makes the trigger zero, so every category with a cap
    // reports APPROACHING. That is odd, but it is the behaviour the summary has had all along and
    // changing it here would alter a shipped screen inside what is meant to be a pure extraction.
    // If a 0% threshold should mean "never alert", that is worth doing on purpose, with its own
    // test and its own line in a release note.
    BigDecimal trigger =
        limit.multiply(BigDecimal.valueOf(pct)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    return spent.compareTo(trigger) >= 0 ? CapState.APPROACHING : null;
  }

  public static String status(BigDecimal utilisationPct, String variance) {
    if (utilisationPct.compareTo(HUNDRED) > 0) {
      return "OVER_BUDGET";
    }
    return "ABOVE".equals(variance) ? "AHEAD_OF_PACE" : "ON_TRACK";
  }

  /**
   * The sentence under the ring, composed here rather than on the client.
   *
   * <p>Singular and plural are handled on both halves independently: "1 day into your budget · 30
   * days left" is a real Monday-of-the-month string, and so is "30 days into your budget · 1 day
   * left".
   */
  public static String daysText(int elapsed, int remaining) {
    return elapsed
        + (elapsed == 1 ? " day" : " days")
        + " into your budget · "
        + remaining
        + (remaining == 1 ? " day left" : " days left");
  }

  /**
   * The middle of a set of monthly totals, used to suggest a first target.
   *
   * <p>A median rather than a mean: one month with a house deposit or a wedding in it would drag an
   * average into a target the customer could never keep, and the point of the suggestion is to
   * offer something they will not immediately blow past.
   */
  public static BigDecimal median(List<BigDecimal> values) {
    if (values == null || values.isEmpty()) {
      return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }
    List<BigDecimal> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int size = sorted.size();
    if (size % 2 == 1) {
      return sorted.get(size / 2).setScale(2, RoundingMode.HALF_UP);
    }
    return sorted
        .get(size / 2 - 1)
        .add(sorted.get(size / 2))
        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
  }
}
