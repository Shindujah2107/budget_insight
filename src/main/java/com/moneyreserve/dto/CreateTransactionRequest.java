package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Adds a transaction the customer entered by hand.
 *
 * <p>This is how cash gets onto the Insights screen. A bank message can only ever describe money
 * that moved through an instrument, so without this the breakdown silently under-reports every
 * customer who pays cash — and in this market that is most of them.
 *
 * <p>There is no user identifier in this body. The owner is the authenticated caller and nothing
 * else; a request that could name its own user would be a request that could write into somebody
 * else's ledger.
 *
 * <p>{@code clientRef} makes a retry safe. A phone that loses its connection after the write but
 * before the response will send the same request again; with a reference we return the transaction
 * that already exists instead of creating a second one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateTransactionRequest {

  /** Always positive. Direction carries the sign. */
  @NotNull(message = "Amount is required.")
  @DecimalMin(value = "0.01", message = "Please enter an amount greater than zero.")
  @Digits(integer = 17, fraction = 2, message = "Please enter a valid amount.")
  private BigDecimal amount;

  /** Defaults to the configured currency when omitted. */
  @Pattern(regexp = "^[A-Z]{3}$", message = "Please provide a three-letter currency code.")
  private String currency;

  /** DEBIT or CREDIT. Defaults to DEBIT: a manual entry is nearly always spend. */
  @Pattern(regexp = "^(DEBIT|CREDIT)$", message = "Direction must be DEBIT or CREDIT.")
  private String direction;

  @Size(max = 128, message = "Merchant name is too long.")
  private String merchant;

  @Size(max = 256, message = "Note is too long.")
  private String description;

  @NotNull(message = "Please choose a date.")
  private LocalDate txnDate;

  /**
   * Chosen category. When omitted the merchant map decides, and the entry is not marked as
   * customer-categorised — so a later improvement to the map may still recategorise it.
   */
  private UUID categoryId;

  /** Omitted for cash, which belongs to no instrument. */
  private UUID accountId;

  /** Client-generated retry key, unique per customer. */
  @Size(max = 96, message = "Client reference is too long.")
  private String clientRef;
}
