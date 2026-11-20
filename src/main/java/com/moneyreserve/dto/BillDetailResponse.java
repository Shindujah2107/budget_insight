package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One bill and its payments, newest first (up to twelve). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillDetailResponse {
  private BillDto bill;
  private List<Paid> payments;
  /** Each due date of the bill and how it stands, newest first (V1.26.0). */
  private List<Cycle> cycles;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Paid {
    private LocalDate date;
    private String amount;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Cycle {
    private java.util.UUID id;
    private LocalDate dueOn;
    /** DUE, PAID, NOT_CONFIRMED or SKIPPED. */
    private String status;
    private String expectedAmount;
    private String actualAmount;
    private LocalDate paidOn;
  }
}
