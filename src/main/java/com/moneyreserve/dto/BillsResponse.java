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
}
