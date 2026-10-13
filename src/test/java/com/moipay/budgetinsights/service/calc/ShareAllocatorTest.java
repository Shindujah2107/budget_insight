package com.moipay.budgetinsights.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one arithmetic invariant on the Insights screen that nothing else can catch.
 *
 * <p>A breakdown whose shares add up to 99.9% is not a crash, does not fail a smoke test and does
 * not show up in a log. It shows up when a customer adds the column themselves — which, on a screen
 * built to answer "where did my money go", some of them will. So the invariant is asserted here,
 * including over randomised input, rather than trusted to the handful of figures anyone thinks to
 * try by hand.
 */
class ShareAllocatorTest {

  @Test
  @DisplayName("shares over the design's eight categories sum to exactly 100")
  void designFiguresSumToOneHundred() {
    List<BigDecimal> amounts =
        amounts(
            "6250.00", "4500.00", "3750.00", "3250.00", "2750.00", "2000.00", "1500.00", "1000.00");

    assertEquals(1000, sum(ShareAllocator.allocateTenths(amounts)));
  }

  @Test
  @DisplayName("three equal thirds still sum to 100, not 99.9")
  void equalThirds() {
    List<BigDecimal> amounts = amounts("100.00", "100.00", "100.00");
    List<Integer> tenths = ShareAllocator.allocateTenths(amounts);

    assertEquals(1000, sum(tenths));
    // 33.3 + 33.3 + 33.3 = 99.9, so exactly one category must be lifted to 33.4.
    assertEquals(1, Collections.frequency(tenths, 334));
    assertEquals(2, Collections.frequency(tenths, 333));
  }

  @Test
  @DisplayName("seven equal sevenths, the worst case for independent rounding")
  void equalSevenths() {
    List<BigDecimal> amounts = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      amounts.add(new BigDecimal("10.00"));
    }
    assertEquals(1000, sum(ShareAllocator.allocateTenths(amounts)));
  }

  @Test
  @DisplayName("a single category takes the whole 100")
  void singleCategory() {
    assertEquals(List.of(1000), ShareAllocator.allocateTenths(amounts("42.00")));
  }

  @Test
  @DisplayName("a category with no spend gets zero and never steals a tenth")
  void zeroAmountsGetZeroShare() {
    List<Integer> tenths = ShareAllocator.allocateTenths(amounts("100.00", "0.00", "0.00"));

    assertEquals(1000, sum(tenths));
    assertEquals(1000, tenths.get(0).intValue());
    assertEquals(0, tenths.get(1).intValue());
    assertEquals(0, tenths.get(2).intValue());
  }

  @Test
  @DisplayName("no spend at all produces all zeros rather than a spurious 100")
  void emptyPeriod() {
    assertEquals(List.of(0, 0), ShareAllocator.allocateTenths(amounts("0.00", "0.00")));
    assertEquals(List.of(), ShareAllocator.allocateTenths(List.of()));
  }

  @Test
  @DisplayName("a rounding tenth goes to the largest remainder, not the largest amount")
  void largestRemainderWins() {
    // Exact shares: 49.95%, 49.95%, 0.10% -> floors 499, 499, 1 = 999. One tenth to give.
    // Both leaders have remainder .5; the tie breaks to the earlier position, deterministically.
    List<Integer> tenths = ShareAllocator.allocateTenths(amounts("999.00", "999.00", "2.00"));

    assertEquals(1000, sum(tenths));
    assertEquals(500, tenths.get(0).intValue());
    assertEquals(499, tenths.get(1).intValue());
    assertEquals(1, tenths.get(2).intValue());
  }

  @Test
  @DisplayName("the same input always produces the same output")
  void deterministic() {
    List<BigDecimal> amounts = amounts("111.11", "222.22", "333.33", "111.11");
    assertEquals(ShareAllocator.allocateTenths(amounts), ShareAllocator.allocateTenths(amounts));
  }

  @Test
  @DisplayName("nulls and negatives are treated as zero rather than throwing")
  void defensiveInput() {
    List<BigDecimal> amounts = new ArrayList<>();
    amounts.add(new BigDecimal("50.00"));
    amounts.add(null);
    amounts.add(new BigDecimal("-10.00"));
    amounts.add(new BigDecimal("50.00"));

    List<Integer> tenths = ShareAllocator.allocateTenths(amounts);
    assertEquals(1000, sum(tenths));
    assertEquals(0, tenths.get(1).intValue());
    assertEquals(0, tenths.get(2).intValue());
  }

  @Test
  @DisplayName("the invariant holds over ten thousand random breakdowns")
  void randomisedInvariant() {
    Random random = new Random(20260912L); // Fixed seed: a failure here is reproducible.

    for (int trial = 0; trial < 10_000; trial++) {
      int n = 1 + random.nextInt(17); // At most the seventeen categories that exist.
      List<BigDecimal> amounts = new ArrayList<>(n);
      boolean anyPositive = false;
      for (int i = 0; i < n; i++) {
        long cents = random.nextInt(5_000_00);
        anyPositive |= cents > 0;
        amounts.add(BigDecimal.valueOf(cents, 2));
      }

      List<Integer> tenths = ShareAllocator.allocateTenths(amounts);
      assertEquals(n, tenths.size(), "one share per category");
      assertEquals(anyPositive ? 1000 : 0, sum(tenths), "shares must sum to exactly 100%");
      for (Integer t : tenths) {
        assertTrue(t >= 0 && t <= 1000, "a share outside 0..100% was produced");
      }
    }
  }

  @Test
  @DisplayName("the percentage view is the tenths view divided by ten")
  void percentageViewAgreesWithTenths() {
    List<BigDecimal> amounts = amounts("1234.56", "765.44", "1000.00");
    List<Integer> tenths = ShareAllocator.allocateTenths(amounts);
    List<Double> pct = ShareAllocator.allocate(amounts);

    assertEquals(tenths.size(), pct.size());
    for (int i = 0; i < tenths.size(); i++) {
      assertEquals(tenths.get(i) / 10.0d, pct.get(i), 0.0d);
    }
  }

  // ---------------------------------------------------------------------------------

  private static List<BigDecimal> amounts(String... values) {
    return Arrays.stream(values).map(BigDecimal::new).toList();
  }

  private static int sum(List<Integer> values) {
    return values.stream().mapToInt(Integer::intValue).sum();
  }
}
