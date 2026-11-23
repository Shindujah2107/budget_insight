package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "Compare a category" (Ask MR picker): this month's categories by share of spending, each with
 * the change against the same dates last month. {@code changed} marks a change that passes the
 * admin thresholds; smaller moves carry no badge. TOO_EARLY on the 1st (no completed day yet):
 * no items.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AskMrCategories {

  /** ANSWERED or TOO_EARLY. */
  private String status;

  private AskMrAnswer.Range period;
  private AskMrAnswer.Range comparePeriod;
  private String currency;
  private String total;

  /** Largest share first. */
  private List<Item> items;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Item {
    private AskMrAnswer.Category category;
    private String amount;
    private Double share;
    private long count;
    /** This period minus the same dates before (negative: lower). */
    private String delta;
    /** Whether the change is meaningful (both admin thresholds): only then the app shows a badge. */
    private boolean changed;
  }
}
