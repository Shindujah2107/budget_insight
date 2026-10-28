package com.moneyreserve.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The budget arithmetic at its boundaries.
 *
 * <p>Every case here is a day a customer will actually have: the first of the month, the last, a
 * month already gone, a month not yet begun, and the day they go past their target. None of them
 * throws when it is wrong — each just puts a number on the ring that is quietly off by one.
 */
class BudgetMathTest {

  private static final InsightsPeriod SEPTEMBER = InsightsPeriod.ofMonth(YearMonth.of(2026, 9));

  @Test
  @DisplayName("the first day of the period counts as one day elapsed, not zero")
  void firstDayIsOneDayElapsed() {
    assertEquals(1, BudgetMath.daysElapsed(SEPTEMBER, LocalDate.of(2026, 9, 1)));
    assertEquals(29, BudgetMath.daysRemaining(SEPTEMBER, LocalDate.of(2026, 9, 1)));
  }

  @Test
  @DisplayName("the last day of the period leaves nothing remaining")
  void lastDayLeavesNothing() {
    assertEquals(30, BudgetMath.daysElapsed(SEPTEMBER, LocalDate.of(2026, 9, 30)));
    assertEquals(0, BudgetMath.daysRemaining(SEPTEMBER, LocalDate.of(2026, 9, 30)));
  }

  @Test
  @DisplayName("a month already past is fully elapsed, never more than its own length")
  void pastMonthIsFullyElapsed() {
    assertEquals(30, BudgetMath.daysElapsed(SEPTEMBER, LocalDate.of(2026, 12, 25)));
    assertEquals(0, BudgetMath.daysRemaining(SEPTEMBER, LocalDate.of(2026, 12, 25)));
  }

  @Test
  @DisplayName("a month not yet begun has elapsed nothing, and does not go negative")
  void futureMonthHasElapsedNothing() {
    assertEquals(0, BudgetMath.daysElapsed(SEPTEMBER, LocalDate.of(2026, 7, 4)));
    assertEquals(30, BudgetMath.daysRemaining(SEPTEMBER, LocalDate.of(2026, 7, 4)));
  }

  @Test
  @DisplayName("utilisation is not capped at 100")
  void utilisationIsNotCapped() {
    assertEquals(
        new BigDecimal("212.5"),
        BudgetMath.utilisationPct(new BigDecimal("8500.00"), new BigDecimal("4000.00")));
  }

  @Test
  @DisplayName("utilisation rounds half up at one decimal place")
  void utilisationRoundsHalfUp() {
    assertEquals(
        new BigDecimal("33.3"),
        BudgetMath.utilisationPct(new BigDecimal("1000.00"), new BigDecimal("3000.00")));
  }

  @Test
  @DisplayName("a daily rate over a period that has not started does not divide by zero")
  void perDayToleratesZeroDays() {
    assertEquals(new BigDecimal("0.00"), BudgetMath.perDay(BigDecimal.ZERO, 0));
  }

  @Test
  @DisplayName("with no tolerance, matching the ideal rate exactly reads as ON")
  void exactlyOnPace() {
    assertEquals("ON", BudgetMath.variance(new BigDecimal("100.00"), new BigDecimal("100.00"), 0));
    assertEquals(
        "ABOVE", BudgetMath.variance(new BigDecimal("100.01"), new BigDecimal("100.00"), 0));
    assertEquals(
        "UNDER", BudgetMath.variance(new BigDecimal("99.99"), new BigDecimal("100.00"), 0));
  }

  @Test
  @DisplayName("a tolerance band absorbs a small drift either side of the ideal rate")
  void toleranceAbsorbsSmallDrift() {
    assertEquals("ON", BudgetMath.variance(new BigDecimal("104.00"), new BigDecimal("100.00"), 5));
    assertEquals("ON", BudgetMath.variance(new BigDecimal("96.00"), new BigDecimal("100.00"), 5));
    assertEquals(
        "ABOVE", BudgetMath.variance(new BigDecimal("105.01"), new BigDecimal("100.00"), 5));
  }

  @Test
  @DisplayName("being over the target outranks pace")
  void overBudgetOutranksPace() {
    // Spent the month's money already, but has since stopped: pace is under, status is not.
    assertEquals("OVER_BUDGET", BudgetMath.status(new BigDecimal("140.0"), "UNDER"));
    assertEquals("AHEAD_OF_PACE", BudgetMath.status(new BigDecimal("40.0"), "ABOVE"));
    assertEquals("ON_TRACK", BudgetMath.status(new BigDecimal("100.0"), "ON"));
  }

  @Test
  @DisplayName("the days sentence gets singular and plural right on each half independently")
  void daysTextHandlesBothHalves() {
    assertEquals("1 day into your budget · 30 days left", BudgetMath.daysText(1, 30));
    assertEquals("30 days into your budget · 1 day left", BudgetMath.daysText(30, 1));
    assertEquals("31 days into your budget · 0 days left", BudgetMath.daysText(31, 0));
  }

  @Test
  @DisplayName("the suggestion takes the middle month, so one exceptional month cannot drag it")
  void medianIgnoresTheOutlier() {
    List<BigDecimal> months =
        List.of(new BigDecimal("28000.00"), new BigDecimal("450000.00"), new BigDecimal("26000.00"));
    assertEquals(new BigDecimal("28000.00"), BudgetMath.median(months));
  }

  @Test
  @DisplayName("an even-sized set takes the mean of the two middle values")
  void medianOfEvenSet() {
    List<BigDecimal> months =
        List.of(new BigDecimal("10000.00"), new BigDecimal("30000.00"),
            new BigDecimal("20000.00"), new BigDecimal("40000.00"));
    assertEquals(new BigDecimal("25000.00"), BudgetMath.median(months));
  }
}
