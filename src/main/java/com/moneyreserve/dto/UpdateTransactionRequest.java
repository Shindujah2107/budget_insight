package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Corrects a transaction. Patch semantics: every field is optional and an omitted field is left
 * alone.
 *
 * <p>{@code categoryId} may be changed on any transaction, including one derived from a bank
 * message — correcting a wrong category is the single most common thing a customer will do on this
 * screen, and refusing it on ingested rows would make the categorisation engine's mistakes
 * permanent. Doing so sets the {@code isUserCategorised} latch, after which no automated pass may
 * change that category again.
 *
 * <p>Every other field may only be changed on a manually added transaction. An ingested row is a
 * record of what the bank said; editing its amount or date would turn the ledger into something
 * that agrees with neither the bank nor the customer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateTransactionRequest {

  /** The customer's chosen category. Allowed on any transaction. */
  private UUID categoryId;

  /** Manual transactions only. */
  @DecimalMin(value = "0.01", message = "Please enter an amount greater than zero.")
  @Digits(integer = 17, fraction = 2, message = "Please enter a valid amount.")
  private BigDecimal amount;

  /** Manual transactions only. */
  @Size(max = 128, message = "Merchant name is too long.")
  private String merchant;

  /** Manual transactions only. */
  @Size(max = 256, message = "Note is too long.")
  private String description;

  /** Manual transactions only. */
  private LocalDate txnDate;

  /** True when the caller means to clear the note rather than leave it unchanged. */
  private Boolean clearDescription;
}
