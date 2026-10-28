package com.moneyreserve.service.calc;

import com.moneyreserve.dto.PeriodDto;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * A closed, validated date range. Both bounds inclusive.
 *
 * <p>Period handling is one place, not scattered across the query methods, because every figure on
 * the Insights screen has to be computed over exactly the same window. A summary bounded one way
 * and a breakdown bounded another produces a total that disagrees with the sum of its own rows,
 * which is the defect that is hardest to see and most damaging to trust in the screen.
 */
public final class InsightsPeriod {

  private final LocalDate start;
  private final LocalDate end;

  private InsightsPeriod(LocalDate start, LocalDate end) {
    this.start = start;
    this.end = end;
  }

  /** The calendar month containing the given date. */
  public static InsightsPeriod ofMonth(YearMonth month) {
    return new InsightsPeriod(month.atDay(1), month.atEndOfMonth());
  }

  /**
   * Resolves the range a request asked for.
   *
   * <p>Both bounds omitted means the current month, which is what the screen opens on. One bound
   * given without the other is completed to that bound's month rather than rejected — a client
   * asking for a month naturally sends its first day.
   */
  public static InsightsPeriod resolve(LocalDate from, LocalDate to, LocalDate today) {
    if (from == null && to == null) {
      return ofMonth(YearMonth.from(today));
    }
    if (from == null) {
      return new InsightsPeriod(YearMonth.from(to).atDay(1), to);
    }
    if (to == null) {
      return new InsightsPeriod(from, YearMonth.from(from).atEndOfMonth());
    }
    return new InsightsPeriod(from, to);
  }

  public LocalDate start() {
    return start;
  }

  public LocalDate end() {
    return end;
  }

  public boolean isInverted() {
    return start.isAfter(end);
  }

  public boolean contains(LocalDate date) {
    return date != null && !date.isBefore(start) && !date.isAfter(end);
  }

  /** True when the range begins before the approved look-back window allows. */
  public boolean startsBefore(LocalDate earliestAllowed) {
    return start.isBefore(earliestAllowed);
  }

  public int days() {
    return (int) ChronoUnit.DAYS.between(start, end) + 1;
  }

  public PeriodDto toDto() {
    return PeriodDto.builder().start(start).end(end).daysInPeriod(days()).build();
  }

  @Override
  public String toString() {
    return start + ".." + end;
  }
}
