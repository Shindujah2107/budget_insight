package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A bank SMS that fits a bill payment the customer confirmed (V1.26.0). {@code linked}: it was
 * made that payment (the app shows "Matched … · Undo"); otherwise the app asks "Is this your …
 * payment?" once.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillMatchDto {
  private UUID billId;
  private String billName;
  /** The bank SMS transaction. */
  private UUID smsTransactionId;
  /** The payment the customer recorded for the bill. */
  private UUID recordedTransactionId;
  /** The SMS's amount and date. */
  private String amount;
  private LocalDate date;
  private Boolean linked;
}
