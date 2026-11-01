package com.moneyreserve.service.calc;

import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds the recurring bills in one customer's payments. Pure: no I/O, so every rule below is unit
 * tested on plain lists.
 *
 * <ol>
 *   <li>Each payment's payee is the known biller whose name terms match its merchant, else the
 *       merchant as {@link MerchantRuleMatcher#normalise} reads it. Bank transfers are left out
 *       unless a biller matches: one transfer description covers many payees.
 *   <li>Per payee, a payment repeated on the same day for the same amount (a re-sent SMS) counts
 *       once, and a refund cancels the payment it returns (same amount, up to 30 days earlier).
 *   <li>The cadence is the one every recent gap fits, allowing one skipped period ({@link
 *       BillCadence}).
 *   <li>Known billers need two payments; any other payee three, with regular amounts. Amounts are
 *       judged on the payments before the latest, so a bill that jumps is still found (and the jump
 *       can be raised as an alert).
 *   <li>The next due date falls on the usual day of the month (month-end stays month-end); a bill
 *       more than two periods overdue has stopped and is not reported.
 * </ol>
 *
 * <p>Dates are the Sri Lanka dates the transactions were recorded on.
 */
public final class BillDetector {

  /** One stored transaction, as detection needs it. */
  public record Payment(
      LocalDate date, BigDecimal amount, boolean moneyIn, String merchant, boolean transfer) {}

  /** A published biller and its compiled name terms. */
  public record KnownBiller(
      UUID id, String code, String name, BillType type, int tolerancePct, MerchantTerms terms) {}

  /** A recurring bill found in the payments. */
  public record Found(
      String payeeKey,
      KnownBiller biller,
      String name,
      BillCadence cadence,
      BigDecimal expectedAmount,
      BigDecimal lastAmount,
      LocalDate lastPaidOn,
      LocalDate nextDueOn,
      Integer anchorDay,
      int paymentCount,
      /** Average of up to three payments before the latest: what a jump is measured against. */
      BigDecimal previousAverage) {}

  /** Recent gaps judged for the cadence; older history does not block a bill that settled. */
  private static final int RECENT_GAPS = 6;

  private static final int UNKNOWN_TOLERANCE_PCT = BillType.OTHER.defaultTolerancePct();
  private static final int MAX_KEY = 160;

  public List<Found> detect(List<Payment> payments, List<KnownBiller> billers, LocalDate today) {
    Map<String, Group> groups = new LinkedHashMap<>();
    for (Payment p : payments) {
      if (p.date() == null || p.amount() == null || p.amount().signum() <= 0) {
        continue;
      }
      Optional<KnownBiller> biller = matchBiller(MerchantText.tokens(p.merchant()), billers);
      Optional<String> key = payeeKey(p, biller);
      if (key.isEmpty()) {
        continue;
      }
      Group g = groups.computeIfAbsent(key.get(), k -> new Group(biller.orElse(null)));
      (p.moneyIn() ? g.refunds : g.paid).add(p);
    }
    List<Found> out = new ArrayList<>();
    for (Map.Entry<String, Group> e : groups.entrySet()) {
      found(e.getKey(), e.getValue(), today).ifPresent(out::add);
    }
    out.sort(Comparator.comparing(Found::nextDueOn).thenComparing(Found::payeeKey));
    return out;
  }

  /** The payee a payment belongs to, or empty when it is not grouped (a plain transfer). */
  public static Optional<String> payeeKey(Payment p, List<KnownBiller> billers) {
    return payeeKey(p, matchBiller(MerchantText.tokens(p.merchant()), billers));
  }

  private static Optional<String> payeeKey(Payment p, Optional<KnownBiller> biller) {
    if (biller.isPresent()) {
      return Optional.of("B:" + biller.get().code());
    }
    String merchant = MerchantRuleMatcher.normalise(p.merchant());
    if (merchant == null || p.transfer()) {
      return Optional.empty();
    }
    return Optional.of(clip("M:" + merchant));
  }

  public static Optional<KnownBiller> matchBiller(List<String> tokens, List<KnownBiller> billers) {
    KnownBiller best = null;
    int bestScore = -1;
    for (KnownBiller b : billers) {
      Optional<MerchantTerms.Hit> hit = b.terms().match(tokens);
      if (hit.isPresent() && hit.get().specificity() > bestScore) {
        best = b;
        bestScore = hit.get().specificity();
      }
    }
    return Optional.ofNullable(best);
  }

  private Optional<Found> found(String key, Group g, LocalDate today) {
    List<Payment> paid = cleaned(g);
    KnownBiller biller = g.biller;
    if (paid.size() < (biller != null ? 2 : 3)) {
      return Optional.empty();
    }
    BillCadence cadence = cadence(paid);
    if (cadence == null) {
      return Optional.empty();
    }
    int tolerance = biller != null ? biller.tolerancePct() : UNKNOWN_TOLERANCE_PCT;
    List<BigDecimal> earlier =
        paid.subList(Math.max(0, paid.size() - 1 - RECENT_GAPS), paid.size() - 1).stream()
            .map(Payment::amount)
            .toList();
    if (!regular(earlier, tolerance)) {
      return Optional.empty();
    }
    Payment last = paid.get(paid.size() - 1);
    // A biller's own name; otherwise the merchant as the latest payment spelled it.
    String name = biller != null ? biller.name() : last.merchant().trim().replaceAll("\\s+", " ");
    Integer anchor = cadence == BillCadence.WEEKLY ? null : anchorDay(paid);
    LocalDate next = nextDue(last.date(), cadence, anchor);
    if (ChronoUnit.DAYS.between(next, today) > 2L * cadence.maxDays()) {
      return Optional.empty(); // stopped: more than two periods overdue
    }
    List<BigDecimal> lastThree =
        paid.subList(Math.max(0, paid.size() - 3), paid.size()).stream().map(Payment::amount).toList();
    List<BigDecimal> before =
        paid.subList(Math.max(0, paid.size() - 4), paid.size() - 1).stream()
            .map(Payment::amount)
            .toList();
    return Optional.of(
        new Found(
            key,
            biller,
            name,
            cadence,
            median(lastThree),
            last.amount(),
            last.date(),
            next,
            anchor,
            paid.size(),
            before.size() >= 2 ? average(before) : null));
  }

  /** Payments by date: same-day repeats of one amount once, refunded payments removed. */
  private static List<Payment> cleaned(Group g) {
    List<Payment> paid = new ArrayList<>(g.paid);
    paid.sort(Comparator.comparing(Payment::date));
    Set<String> seen = new LinkedHashSet<>();
    paid.removeIf(p -> !seen.add(p.date() + "|" + p.amount().stripTrailingZeros().toPlainString()));
    List<Payment> refunds = new ArrayList<>(g.refunds);
    refunds.sort(Comparator.comparing(Payment::date));
    for (Payment refund : refunds) {
      for (int i = paid.size() - 1; i >= 0; i--) {
        Payment p = paid.get(i);
        long daysBefore = ChronoUnit.DAYS.between(p.date(), refund.date());
        if (p.amount().compareTo(refund.amount()) == 0 && daysBefore >= 0 && daysBefore <= 30) {
          paid.remove(i);
          break;
        }
      }
    }
    return paid;
  }

  /** The cadence every recent gap fits, with at most one skipped period. */
  static BillCadence cadence(List<Payment> paid) {
    List<Long> gaps = new ArrayList<>();
    for (int i = Math.max(1, paid.size() - RECENT_GAPS); i < paid.size(); i++) {
      gaps.add(ChronoUnit.DAYS.between(paid.get(i - 1).date(), paid.get(i).date()));
    }
    for (BillCadence c : BillCadence.values()) {
      int normal = 0;
      int skipped = 0;
      boolean all = true;
      for (long gap : gaps) {
        if (c.fits(gap)) {
          normal++;
        } else if (c.fitsSkipped(gap)) {
          skipped++;
        } else {
          all = false;
          break;
        }
      }
      if (all && normal >= 1 && skipped <= 1) {
        return c;
      }
    }
    return null;
  }

  private static boolean regular(List<BigDecimal> amounts, int tolerancePct) {
    if (amounts.isEmpty()) {
      return true;
    }
    BigDecimal mid = median(amounts);
    if (mid.signum() == 0) {
      return false;
    }
    BigDecimal allowed = mid.multiply(BigDecimal.valueOf(tolerancePct)).divide(BigDecimal.valueOf(100));
    return amounts.stream().allMatch(a -> a.subtract(mid).abs().compareTo(allowed) <= 0);
  }

  /** The usual day of the month: median of the last three, where a month's last day counts as 31. */
  private static int anchorDay(List<Payment> paid) {
    List<Integer> days = new ArrayList<>();
    for (Payment p : paid.subList(Math.max(0, paid.size() - 3), paid.size())) {
      LocalDate d = p.date();
      days.add(d.getDayOfMonth() == d.lengthOfMonth() ? 31 : d.getDayOfMonth());
    }
    days.sort(Integer::compare);
    return days.get(days.size() / 2);
  }

  /** The first date on the anchor day that is one period after the last payment. */
  public static LocalDate nextDue(LocalDate last, BillCadence cadence, Integer anchor) {
    if (cadence == BillCadence.WEEKLY || anchor == null) {
      return last.plusDays(cadence == BillCadence.WEEKLY ? 7 : (cadence.minDays() + cadence.maxDays()) / 2);
    }
    int months =
        switch (cadence) {
          case MONTHLY -> 1;
          case QUARTERLY -> 3;
          default -> 12;
        };
    for (int m = months - 1; m <= months + 1; m++) {
      LocalDate month = last.plusMonths(m);
      LocalDate candidate = month.withDayOfMonth(Math.min(anchor, month.lengthOfMonth()));
      long gap = ChronoUnit.DAYS.between(last, candidate);
      if (cadence.fits(gap)) {
        return candidate;
      }
    }
    LocalDate month = last.plusMonths(months);
    return month.withDayOfMonth(Math.min(anchor, month.lengthOfMonth()));
  }

  static BigDecimal median(List<BigDecimal> values) {
    List<BigDecimal> sorted = new ArrayList<>(values);
    sorted.sort(BigDecimal::compareTo);
    int n = sorted.size();
    BigDecimal mid =
        n % 2 == 1
            ? sorted.get(n / 2)
            : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2));
    return mid.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal average(List<BigDecimal> values) {
    return values.stream()
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
  }

  private static String clip(String key) {
    return key.length() <= MAX_KEY ? key : key.substring(0, MAX_KEY);
  }

  private static final class Group {
    final KnownBiller biller;
    final List<Payment> paid = new ArrayList<>();
    final List<Payment> refunds = new ArrayList<>();

    Group(KnownBiller biller) {
      this.biller = biller;
    }
  }
}
