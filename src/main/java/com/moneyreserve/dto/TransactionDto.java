package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One transaction inside an expanded category row.
 *
 * <p>{@code amount} is the unsigned value and {@code displayAmount} is what the screen shows,
 * already signed and formatted. Both are present so the client does not have to re-derive the sign
 * from the direction and risk disagreeing with the backend about it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionDto {
  private UUID id;
  private String merchant;
  private String description;
  private LocalDate date;
  private String amount;
  private String direction;
  private String displayAmount;
  private UUID categoryId;
  private UUID accountId;
  private String accountLabel;
  private String source;
  private Boolean editable;
  private Boolean isUserCategorised;
  /**
   * Why the row has its category: RULE, USER or FALLBACK (nothing matched; filed under Other and
   * shown as needing a category). Absent on rows not yet backfilled.
   */
  private String categorySource;
  /** {@code CASH_WALLET} when the cash wallet paid (or received) it. */
  private String paymentSource;
  /** A confirmed own-money movement: neither spending nor income. */
  private String movement;

  /**
   * The bank this transaction came from (V1.25.0), when known: the bank of its SMS, else of its
   * account. Lets the app pair money leaving one of the customer's banks with money arriving at
   * another even when the SMS shows too few account digits to name the account.
   */
  private String bankCode;
}
