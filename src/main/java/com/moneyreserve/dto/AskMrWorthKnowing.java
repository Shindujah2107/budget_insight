package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A "Worth knowing" card (Ask MR, V1.29.0): one meaningful change this month, at most N a week per
 * customer, never the same topic twice in 14 days. Opening it asks {@code question} about
 * {@code category}. Absent ({@code card} null) when there is nothing worth showing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AskMrWorthKnowing {

  private Card card;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Card {
    private UUID id;
    /** e.g. CATEGORY:DINING_OUT:UP. */
    private String topic;
    /** INCREASE or DECREASE. */
    private String kind;
    private AskMrAnswer.Category category;
    /** The change (positive); the period is {@code period}. */
    private String delta;
    private AskMrAnswer.Range period;
    private AskMrAnswer.Range comparePeriod;
    /** CATEGORY_CHANGE. */
    private String question;
  }
}
