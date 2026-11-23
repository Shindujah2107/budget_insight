package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What Ask MR can answer for this customer now (V1.29.0). A question without enough recorded data
 * is listed with {@code available=false} and a reason, never answered weakly.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AskMrQuestions {

  /** Entitled to Premium: full answers. Otherwise each answer is the teaser (headline only). */
  private boolean entitled;

  private boolean weeklySummary;
  private List<Question> questions;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Question {
    private String id;
    private boolean available;
    /** NOT_ENOUGH_DATA or TOO_EARLY when unavailable. */
    private String reason;
  }
}
