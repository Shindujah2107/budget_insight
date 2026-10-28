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
}
