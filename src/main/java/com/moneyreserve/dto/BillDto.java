package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One of the customer's recurring bills. Amounts are money strings ({@code "4100.00"}) like every
 * other Insights amount. {@code biller*} fields are present when a known biller matched.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillDto {
  private UUID id;
  /** The customer's name for it if they renamed it, else the detected one. */
  private String name;
  private String detectedName;
  /** DETECTED, CONFIRMED, DISMISSED or MANUAL. */
  private String status;
  /** WEEKLY, MONTHLY, QUARTERLY or YEARLY. */
  private String cadence;
  private String expectedAmount;
  private String lastAmount;
  private LocalDate lastPaidOn;
  private LocalDate nextDueOn;
  /** Negative when overdue. */
  private Long daysUntilDue;
  private Short remindDaysBefore;
  private Integer paymentCount;
  private String billerCode;
  private String billerNameSi;
  private String billerNameTa;
  /** ELECTRICITY, WATER, TELECOM, STREAMING, INSURANCE, LOAN, CARD, RENT or OTHER. */
  private String billType;
  private String iconKey;
  private String colourHex;
  private String currency;

  /**
   * True when this cycle is already paid (V1.23.0): no "Did you pay it?" for this due date. For a
   * found bill the app also asks only when the due date has passed without a payment.
   */
  private Boolean paidThisCycle;

  /** The bill's own category (V1.24.0), when bill categories are on and one was chosen. */
  private UUID categoryId;
}
