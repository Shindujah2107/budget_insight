package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "What changed?": recorded spending per category, this period against the one before (V1.28.0).
 * Facts only (amounts, counts, dates); never a reason. See docs/features/what-changed/API.md.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WhatChangedResponse {

  /** AVAILABLE, NO_CHANGES, NO_COMPLETED_DAYS or INSUFFICIENT_HISTORY. */
  private String status;

  /** MONTH_TO_DATE (this month, through yesterday) or COMPLETED_MONTH. */
  private String mode;

  /** The month asked about, YYYY-MM. */
  private String month;

  private Range currentPeriod;
  private Range previousPeriod;

  private String currency;

  /** The accounts applied; empty means all accounts (cash included). */
  private List<UUID> accountIds;

  private Coverage coverage;

  /** At most three, largest absolute difference first; empty unless AVAILABLE. */
  private List<Change> changes;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Range {
    /** Inclusive. */
    private LocalDate startDate;
    /** Inclusive. */
    private LocalDate endDate;
    private int days;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static class Coverage {
    /** The earliest recorded transaction on the chosen accounts; null when there is none. */
    private LocalDate earliestRecordOn;
    /** Records reach back to the start of the previous period. */
    private boolean previousPeriodCovered;
    /** Spending transactions counted, per period. */
    private long currentTransactionCount;
    private long previousTransactionCount;
    /** Spending in another currency, left out (never summed). */
    private long otherCurrencyExcludedCount;
    /**
     * Always true: figures cover recorded transactions only, which does not prove every bank SMS
     * or payment was captured.
     */
    private boolean recordedTransactionsOnly;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static class Change {
    private UUID categoryId;
    private String code;
    private String name;
    private String nameSi;
    private String nameTa;
    private String iconKey;
    private String colourHex;

    /** CHANGED, NEW_THIS_PERIOD (nothing before), NONE_THIS_PERIOD (nothing now) or NET_REFUND. */
    private String state;

    private Side current;
    private Side previous;

    /** Signed: current − previous. */
    private String difference;
    /** INCREASE or DECREASE. */
    private String direction;

    /** (current − previous) ÷ previous × 100, one decimal; null for NEW_THIS_PERIOD / NET_REFUND. */
    private BigDecimal percentChange;

    /** Signed: current count − previous count. */
    private long paymentCountDifference;

    private Drilldown currentTransactions;
    private Drilldown previousTransactions;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static class Side {
    private String total;
    private long paymentCount;
    /** total ÷ payments, when both are positive; else null. */
    private String averagePayment;
  }

  /** Exactly the parameters to open that period's transactions for the category. */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public static class Drilldown {
    /** GET path: /api/insights/breakdown/{categoryId}/transactions */
    private String path;
    private UUID categoryId;
    private LocalDate from;
    private LocalDate to;
    private String currency;
    /** Repeat as accountId=…; empty means all accounts. */
    private List<UUID> accountIds;
  }
}
