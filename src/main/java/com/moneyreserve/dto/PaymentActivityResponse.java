package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Drives the payment activity chart.
 *
 * <p>Both series always return one point per day of the selected week, with "0.00" for a day with
 * no spend rather than an omitted entry. A sparse series would make the client responsible for
 * deciding whether a gap is a zero or missing data, and the two render differently.
 *
 * <p><b>Both series are always present, including when the customer has filtered to one account.</b>
 * An account is either a card or a bank account, so filtering to one leaves the other series with
 * nothing in it — and it is returned as seven zeros rather than dropped, so the chart never has to
 * change shape in response to a filter.
 *
 * <p>The chart covers money that moved on an instrument. {@link #unattributedTotal} carries what
 * it cannot show.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentActivityResponse {

  private PeriodDto range;
  private String currency;

  /** Highest value across both series, so the client can scale the axis without scanning. */
  private String maxValue;

  private List<Series> series;

  /**
   * Spend in the window that belongs to no instrument, so appears in neither series.
   *
   * <p>Cash and manual entries have no account by design; so, at present, does every ingested card
   * transaction, because {@code masked-card-to-backend-enabled} is off and the tail is dropped on
   * arrival. Without this field a customer comparing the chart against the breakdown for the same
   * week finds money missing and nothing to explain it.
   *
   * <p>Additive to the contract published in R0 and omitted when zero, so a client that ignores it
   * behaves exactly as before.
   */
  private String unattributedTotal;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Series {
    /** CARD or BANK. */
    private String key;

    private String label;
    private List<Point> points;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Point {
    private LocalDate date;
    private String amount;
  }
}
