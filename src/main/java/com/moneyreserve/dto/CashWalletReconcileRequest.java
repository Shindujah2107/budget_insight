package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The customer's answer to "does this match your cash?". */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashWalletReconcileRequest {

  @Pattern(regexp = "^[A-Z]{3}$", message = "Please provide a three-letter currency code.")
  private String currency;

  /** The same on every retry of one operation. */
  @NotBlank(message = "A client reference is required.")
  @Size(max = 96, message = "Client reference is too long.")
  private String clientRef;

  /** The wallet version the customer was looking at. */
  @NotBlank(message = "The wallet version is required.")
  @Size(max = 20, message = "The wallet version is not valid.")
  private String expectedVersion;

  /** The cash the customer says they hold: a non-negative amount with at most two decimals. */
  @NotBlank(message = "Please enter the cash you have.")
  @Size(max = 20, message = "Please enter a valid amount.")
  private String actual;

  @Pattern(regexp = "^(SPENT|DEPOSIT|CORRECTION|INCOME|TRANSFER)$", message = "Please choose a reason.")
  private String reason;

  /**
   * Opening only: the amount already includes ATM withdrawals made today, so one the phone imports
   * later, dated today, is not added again.
   */
  private Boolean openingIncludesToday;

  private UUID categoryId;
  private UUID accountId;
  private UUID matchingTransactionId;
}
