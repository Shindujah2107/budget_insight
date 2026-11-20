package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.moneyreserve.exception.BudgetInsightsException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** "What changed?" periods (V1.28.0): which dates are compared, for each kind of month. */
@DisplayName("What changed? periods")
class WhatChangedPeriodsTest {

  private static WhatChangedServiceImpl.Periods at(String month, String today) {
    return WhatChangedServiceImpl.periods(YearMonth.parse(month), LocalDate.parse(today));
  }

  private static void same(WhatChangedServiceImpl.Periods p, String cs, String ce, String ps, String pe) {
    assertEquals(LocalDate.parse(cs), p.curStart());
    assertEquals(LocalDate.parse(ce), p.curEnd());
    assertEquals(LocalDate.parse(ps), p.prevStart());
    assertEquals(LocalDate.parse(pe), p.prevEnd());
  }

  @Test
  @DisplayName("this month: completed days through yesterday against the same dates last month")
  void monthToDate() {
    WhatChangedServiceImpl.Periods p = at("2026-11", "2026-11-16");
    assertEquals("MONTH_TO_DATE", p.mode());
    same(p, "2026-11-01", "2026-11-15", "2026-10-01", "2026-10-15");
  }

  @Test
  @DisplayName("unequal months: both capped to the days both have (31 March vs February)")
  void capped() {
    same(at("2026-03", "2026-03-31"), "2026-03-01", "2026-03-28", "2026-02-01", "2026-02-28");
    same(at("2026-05", "2026-05-31"), "2026-05-01", "2026-05-30", "2026-04-01", "2026-04-30");
  }

  @Test
  @DisplayName("leap year: February has 29 days")
  void leapYear() {
    same(at("2028-03", "2028-03-31"), "2028-03-01", "2028-03-29", "2028-02-01", "2028-02-29");
    same(at("2028-02", "2028-03-05"), "2028-02-01", "2028-02-29", "2028-01-01", "2028-01-31");
  }

  @Test
  @DisplayName("year boundary: January against December of the year before")
  void yearBoundary() {
    same(at("2027-01", "2027-01-10"), "2027-01-01", "2027-01-09", "2026-12-01", "2026-12-09");
    same(at("2026-12", "2027-01-10"), "2026-12-01", "2026-12-31", "2026-11-01", "2026-11-30");
  }

  @Test
  @DisplayName("a completed month: all of it against all of the month before, even when they differ in length")
  void completedMonth() {
    WhatChangedServiceImpl.Periods p = at("2026-03", "2026-11-15");
    assertEquals("COMPLETED_MONTH", p.mode());
    same(p, "2026-03-01", "2026-03-31", "2026-02-01", "2026-02-28");
  }

  @Test
  @DisplayName("the 1st of the month: no completed days; a future month is refused")
  void firstDayAndFuture() {
    WhatChangedServiceImpl.Periods first = at("2026-11", "2026-11-01");
    assertNull(first.curStart());
    assertNull(first.prevStart());
    assertThrows(BudgetInsightsException.class, () -> at("2026-12", "2026-11-30"));
  }

  @Test
  @DisplayName("timezone: the app's own calendar day decides (Colombo, not UTC)")
  void timezone() {
    // 2026-11-30T19:00Z is already 1 December 00:30 in Colombo: no completed days of December.
    Instant late = Instant.parse("2026-11-30T19:00:00Z");
    LocalDate colombo = LocalDate.ofInstant(late, ZoneId.of("Asia/Colombo"));
    LocalDate utc = LocalDate.ofInstant(late, ZoneId.of("UTC"));
    assertNull(WhatChangedServiceImpl.periods(YearMonth.from(colombo), colombo).curStart());
    same(WhatChangedServiceImpl.periods(YearMonth.from(utc), utc), "2026-11-01", "2026-11-29", "2026-10-01", "2026-10-29");
  }
}
