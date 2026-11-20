package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The customer's bills (dismissed ones left out) with their monthly fixed-cost total. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BillsResponse {
  private List<BillDto> bills;
  /** Every bill as a monthly figure (weekly × 52 / 12, quarterly / 3, yearly / 12), summed. */
  private String monthlyTotal;
  private String currency;
  /** True when Premium-only bill features are switched on (see the plan's free/Premium table). */
  private boolean premiumGate;

  /** Bills can carry their own category (V1.24.0): the app asks for one when a bill is added. */
  private boolean categoriesEnabled;

  /**
   * Recurring payments found but not added (V1.27.0): "Possible recurring payments", for the
   * customer to review. Never part of {@code bills} or the monthly total.
   */
  private List<BillDto> suggestions;

  /** Bill identification is on for this customer (free, or Premium when it is a Premium feature). */
  private boolean suggestionsEnabled;
}
