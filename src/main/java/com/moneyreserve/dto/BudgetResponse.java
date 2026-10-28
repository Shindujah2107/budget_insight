package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The stored target for one period, as the set-a-budget screen reads it back.
 *
 * <p>Amounts are decimal strings, for the reason given on {@link BudgetSummaryResponse}.
 *
 * <p>A period with no target is not a 404. The screen's job in that state is to offer to set one,
 * and it needs the period bounds and the currency to do that, so the same shape comes back with
 * {@code budgetSet} false and the target fields absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetResponse {

  private PeriodDto period;
  private boolean budgetSet;
  private String currency;

  private String overallLimit;

  /** True when this period's target began as the previous period's, carried forward. */
  private Boolean rolledForward;

  /**
   * The budget policy's default "approaching limit" threshold, set by an admin in the Admin
   * Dashboard: where a "warn me at" slider starts, and what a cap without its own value uses.
   */
  private Integer defaultAlertThresholdPct;

  private List<CategoryLimitDto> categoryLimits;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CategoryLimitDto {
    private UUID categoryId;
    private String categoryCode;
    private String categoryName;
    private String limitAmount;
    private Integer alertThresholdPct;

    /**
     * True when the customer did not choose a threshold for this cap: {@code alertThresholdPct}
     * is then the policy default and follows it when an admin changes it.
     */
    private Boolean followsDefaultThreshold;
  }
}
