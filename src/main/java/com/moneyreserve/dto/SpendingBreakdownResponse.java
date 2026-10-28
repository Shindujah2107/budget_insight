package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Drives the spending breakdown: the period total header and the ranked category rows.
 *
 * <p>{@code sharePct} values are computed by the backend and adjusted so the returned set sums to
 * exactly 100. The client renders the bar from this number and does not recalculate it — two
 * independent roundings of the same figures is how a list ends up displaying 99% or 101%.
 *
 * <p>Categories with no spend in the period are excluded. The list is complete, so there is no "see
 * all" call: at most 17 rows come back.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SpendingBreakdownResponse {

  private PeriodDto range;
  private String currency;

  /** The figure behind the "Total Spending money" header. */
  private String total;

  private Integer categoryCount;
  private Integer returnedCount;
  private List<CategorySpend> categories;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CategorySpend {
    private UUID categoryId;
    private String code;
    private String name;
    private String iconKey;
    private String amount;
    private Double sharePct;
    private Integer transactionCount;

    /** Whether expanding this row will return any transactions. */
    private Boolean hasMore;
  }
}
