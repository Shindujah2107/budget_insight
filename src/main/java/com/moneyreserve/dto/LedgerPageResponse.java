package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One page of the customer's recorded transactions, money out and money in, with the totals for the
 * whole filtered period (not just this page).
 *
 * <p>{@code totals.incomeCoverage} is always {@code RECORDED}: money in is only what reached this
 * service (bank alerts from the banks the customer chose, and entries they added). It is not a
 * complete income history, so no net flow is returned.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LedgerPageResponse {

  private PeriodDto range;
  private String currency;
  /** DEBIT, CREDIT, or absent for both. */
  private String direction;
  private Totals totals;
  private CategoryTransactionsResponse.PageDto page;
  private List<TransactionDto> transactions;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Totals {
    private String moneyOut;
    private long moneyOutCount;
    private String moneyIn;
    private long moneyInCount;
    /** What the money-in figure covers. Only {@code RECORDED} today. */
    private String incomeCoverage;
    /** Money out that no rule matched (category source FALLBACK): "needs a category". */
    private String uncategorised;
    private long uncategorisedCount;
  }
}
