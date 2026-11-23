package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One Ask MR answer (V1.29.0) as structured facts: the app writes the sentences (EN/SI/TA) from
 * them, so every figure shown comes from here and nothing is phrased on the server. Four parts:
 * {@code headline} (what happened), {@code drivers} (what contributed), {@code tips} (what could
 * help, optional) and {@code evidence} (where to look). {@code progress} lists improvements to show
 * first; {@code notes} explain missing or partial data.
 *
 * <p>Without Premium ({@code premiumLocked}) only the headline, the notes and the plain
 * transaction evidence are sent; drivers, progress and tips are left out on the server.
 *
 * <p>Amounts are strings in the API's money format, positive unless named a difference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AskMrAnswer {

  /** SPENDING_CHANGE, CATEGORY_CHANGE, WHERE_MONEY_WENT, BUDGET, IMPROVING or WEEK. */
  private String question;

  /** ANSWERED, TOO_EARLY (month comparisons before the early-month day) or NOT_ENOUGH_DATA. */
  private String status;

  private Range period;

  /** The period compared against; absent for questions that compare nothing. */
  private Range comparePeriod;

  private String currency;
  private List<UUID> accountIds;
  private boolean premiumLocked;

  /**
   * Without Premium only: how much the locked part holds (counts, never the content), so the
   * teaser can say what is waiting.
   */
  private LockedPreview lockedPreview;

  private Headline headline;
  private List<Driver> progress;
  private List<Driver> drivers;
  private List<Tip> tips;
  private List<Evidence> evidence;
  private List<Note> notes;
  private List<FollowUp> followUps;

  /**
   * Premium: this period next to the same dates before, with count and total for each side (and,
   * for one category, the payments themselves). Left out without Premium.
   */
  private SideBySide sideBySide;

  /**
   * Premium: where the month ends at the current pace. Month questions only, never before the
   * early-month day, and never when a one-off explains the change (the pace would mislead).
   */
  private Forecast forecast;

  /** Premium: the last three full months and this month so far (oldest first). */
  private List<TrendPoint> trend;

  /** Premium, one category: where its money went this period (clear merchant names only). */
  private List<Place> topPlaces;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class SideBySide {
    private Side current;
    private Side previous;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Side {
    private LocalDate from;
    private LocalDate to;
    private long count;
    private String amount;
    /** One category only: the payments, newest first (at most five; {@code count} has them all). */
    private List<Payment> payments;
  }

  /** {@code merchant} is left out when the recorded name says nothing (POS, a terminal code…). */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Payment {
    private UUID transactionId;
    private LocalDate date;
    private String merchant;
    private String amount;
  }

  /** {@code amount}: the estimate for {@code month} (rounded); {@code previousMonth}: the whole last month. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Forecast {
    private String month;
    private String amount;
    private String previousMonth;
  }

  /** {@code month} as yyyy-MM; {@code partial} for this month so far. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class TrendPoint {
    private String month;
    private String amount;
    private boolean partial;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Place {
    private String merchant;
    private String amount;
    private long count;
    private Double share;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class LockedPreview {
    /** Reasons found: what contributed plus the progress lines. */
    private int reasons;
    /** Suggestions under "What could help". */
    private int suggestions;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Range {
    private LocalDate from;
    private LocalDate to;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Category {
    private UUID id;
    private String code;
    private String name;
    private String nameSi;
    private String nameTa;
    /** The category's spending type (V1.30.0): BILL, ESSENTIAL, FLEXIBLE, EXCLUDED; absent when not set. */
    private String type;
  }

  /**
   * What happened. {@code kind}: INCREASE, DECREASE, STEADY (no meaningful change), SUMMARY (where
   * the money went), OVER_TARGET, UNDER_TARGET, NO_TARGET, PROGRESS (improvements found) or
   * NO_PROGRESS. {@code subject}: TOTAL, CATEGORY, BUDGET or WEEK.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Headline {
    private String kind;
    private String subject;
    private Category category;
    /** This period's amount (spent, or for budgets the spending so far). */
    private String amount;
    /** The compared period's amount, or the budget target. */
    private String previous;
    /** amount − previous (signed). */
    private String delta;
    /** Percentage change against {@code previous}, one decimal; absent when previous is zero. */
    private Double percent;
    private Long count;
    private Long previousCount;
    /** Budgets: days left in the month. */
    private Integer daysRemaining;
  }

  /**
   * One contributor. {@code kind}: MORE_PURCHASES, FEWER_PURCHASES, BIGGER_PURCHASES,
   * SMALLER_PURCHASES, NEW_MERCHANT, ONE_OFF, CATEGORY_UP, CATEGORY_DOWN, OVER_LIMIT, NEAR_LIMIT,
   * TOP_CATEGORY or TOP_MERCHANT. Only the fields that apply are present.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Driver {
    private String kind;
    private Category category;
    /** The display-safe merchant as recorded (never SMS text). */
    private String merchant;
    private Long count;
    private Long previousCount;
    private String amount;
    private String previousAmount;
    /** Signed change this driver accounts for. */
    private String delta;
    private String averagePayment;
    private String previousAveragePayment;
    /** Share of the period's spending, percent with one decimal. */
    private Double share;
    private String limit;
    /** ONE_OFF: the payment itself, and why it reads as one: LARGE_PAYMENT or YEARLY_BILL. */
    private UUID transactionId;
    private LocalDate date;
    private String reason;
  }

  /** An optional suggestion: the tip's wording in each language, with {placeholders} to fill. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Tip {
    private String id;
    private String en;
    private String si;
    private String ta;
    /** category (a category code), merchant, amount (money), previousPeriod (YYYY-MM). */
    private Map<String, String> params;
  }

  /**
   * Where to look. {@code kind}: TRANSACTIONS (a category's comparable transactions for the
   * dates; {@code path} is the drilldown), TRANSACTION (one payment), BUDGET (the month's budget),
   * UNCATEGORISED (spending without a category). Opening one never changes anything.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Evidence {
    private String kind;
    private String path;
    private Category category;
    private LocalDate from;
    private LocalDate to;
    private String currency;
    private List<UUID> accountIds;
    private UUID transactionId;
    private String month;
  }

  /**
   * Why an answer may be partial. {@code kind}: INCOME_INCOMPLETE, UNCATEGORISED_HIGH (with the
   * amount), SMS_GAP (no bank SMS for some days), EARLY_MONTH, OTHER_CURRENCY (rows left out).
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Note {
    private String kind;
    private String amount;
    private Long count;
    private LocalDate since;
  }

  /** A question worth asking next, e.g. CATEGORY_CHANGE for the category that grew most. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class FollowUp {
    private String question;
    private Category category;
  }
}
