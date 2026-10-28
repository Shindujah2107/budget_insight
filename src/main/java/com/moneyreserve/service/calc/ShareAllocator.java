package com.moneyreserve.service.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Turns category totals into percentage shares that add up to exactly 100.
 *
 * <p>The naive approach — round each share independently — produces lists that display 99.9% or
 * 100.1%, because rounding each of eight numbers to one decimal place discards up to half a tenth
 * eight times over. On a screen whose whole point is "where did my money go", a column that does
 * not add up is the kind of defect a customer reports and an auditor asks about.
 *
 * <p>This class uses the largest-remainder method. Each share is computed exactly in tenths of a
 * percent, truncated, and the tenths lost to truncation are handed back one at a time to the
 * categories that lost the most. That is deterministic, it never invents money, and it is the same
 * rule used for apportioning seats, so it is defensible when somebody asks why a particular
 * category shows 12.4% rather than 12.3%.
 *
 * <p><b>The invariant:</b> for any input with a positive total, the returned tenths sum to exactly
 * {@link #TOTAL_TENTHS}. The percentage values are that figure divided by ten, so they sum to 100
 * when added as tenths. They are deliberately not guaranteed to sum to exactly 100.0 in binary
 * floating point — 0.1 has no exact double representation — which is one more reason the client
 * renders these numbers and does not add them up.
 */
public final class ShareAllocator {

  /** 100.0%, expressed in tenths of a percent. */
  public static final int TOTAL_TENTHS = 1000;

  private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

  private ShareAllocator() {}

  /**
   * Allocates percentage shares over the given amounts.
   *
   * @param amounts the category totals, in the order they will be displayed. Nulls and negatives
   *     are treated as zero.
   * @return one share per input, in the same order, each a percentage to one decimal place.
   */
  public static List<Double> allocate(List<BigDecimal> amounts) {
    List<Integer> tenths = allocateTenths(amounts);
    List<Double> out = new ArrayList<>(tenths.size());
    for (Integer t : tenths) {
      out.add(t / 10.0d);
    }
    return out;
  }

  /**
   * The same allocation, left in tenths of a percent.
   *
   * <p>This is the form the invariant is stated over, and the form a test should assert on: the
   * returned list sums to exactly {@link #TOTAL_TENTHS} whenever the input total is positive.
   */
  public static List<Integer> allocateTenths(List<BigDecimal> amounts) {
    int n = amounts == null ? 0 : amounts.size();
    List<Integer> result = new ArrayList<>(Collections.nCopies(Math.max(n, 0), 0));
    if (n == 0) {
      return result;
    }

    List<BigDecimal> clean = new ArrayList<>(n);
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal a : amounts) {
      BigDecimal v = (a == null || a.signum() < 0) ? BigDecimal.ZERO : a;
      clean.add(v);
      total = total.add(v);
    }

    // No spend at all. Every share is zero; there is nothing to apportion and nothing to fix up.
    if (total.signum() <= 0) {
      return result;
    }

    BigDecimal scale = BigDecimal.valueOf(TOTAL_TENTHS);
    List<Slot> slots = new ArrayList<>(n);
    int allocated = 0;

    for (int i = 0; i < n; i++) {
      BigDecimal exact = clean.get(i).multiply(scale, MC).divide(total, MC);
      int floor = exact.setScale(0, RoundingMode.FLOOR).intValueExact();
      BigDecimal remainder = exact.subtract(BigDecimal.valueOf(floor));
      result.set(i, floor);
      allocated += floor;
      slots.add(new Slot(i, remainder, clean.get(i)));
    }

    // Hand back what truncation took, largest remainder first. Ties go to the larger amount, and
    // then to the earlier position, so the same input always produces the same output.
    int deficit = TOTAL_TENTHS - allocated;
    if (deficit <= 0) {
      return result;
    }
    slots.sort(
        Comparator.comparing((Slot s) -> s.remainder)
            .reversed()
            .thenComparing(Comparator.comparing((Slot s) -> s.amount).reversed())
            .thenComparingInt(s -> s.index));

    for (int k = 0; k < deficit && k < slots.size(); k++) {
      int idx = slots.get(k).index;
      result.set(idx, result.get(idx) + 1);
    }
    return result;
  }

  private static final class Slot {
    private final int index;
    private final BigDecimal remainder;
    private final BigDecimal amount;

    private Slot(int index, BigDecimal remainder, BigDecimal amount) {
      this.index = index;
      this.remainder = remainder;
      this.amount = amount;
    }
  }
}
