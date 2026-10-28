package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Drives the monthly budget card.
 *
 * <p>Amounts are decimal strings rather than numbers. JSON numbers are doubles in most clients, and
 * a currency total that has been through a double is a total that can disagree with the backend by
 * a cent. The client formats the string; it never does arithmetic on it.
 *
 * <p>When no target is set, {@code budgetSet} is false and every budget-dependent field is omitted
 * rather than returned as zero — a zero target and an absent target look identical to a client
 * otherwise, and they mean very different things. {@code suggestion} is returned instead.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetSummaryResponse {

  private PeriodDto period;
  private boolean budgetSet;
  private String currency;

  private String monthlyTarget;
  private String spentToDate;
  private String remaining;
  private Double utilisationPct;

  /** ON_TRACK, AHEAD_OF_PACE or OVER_BUDGET. */
  private String status;

  private Integer daysElapsed;
  private Integer daysRemaining;

  /** Pre-composed, for example "5 days into your budget · 26 days left". */
  private String daysText;

  private Pace pace;
  private List<CategoryAlert> categoryAlerts;
  private Suggestion suggestion;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Pace {
    private String averageDailySpend;
    private String idealDailySpend;

    /** ABOVE, ON or UNDER the pace needed to stay within the target. */
    private String variance;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CategoryAlert {
    private String categoryCode;
    private String limit;
    private String spent;

    /** APPROACHING or EXCEEDED. */
    private String state;
  }

  /** Returned only when no target is set, to seed the set-a-budget prompt. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Suggestion {
    private String suggestedTarget;
    private String basis;

    /** Months the suggestion is the median of (the budget policy's recommendation period). */
    private Integer periodMonths;
  }
}
