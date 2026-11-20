package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A suggested recurring payment and why (V1.27.0): the customer's own payments it was found from,
 * newest first, and over how many months.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillSuggestionResponse {
  private BillDto bill;
  private List<Evidence> payments;
  private Integer months;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Evidence {
    private UUID transactionId;
    private LocalDate date;
    private String amount;
    /** What the bank called it (the merchant). */
    private String description;
    /** The card or account's last digits, when known. */
    private String accountMask;
    /** CARD or BANK. */
    private String accountKind;
  }
}
