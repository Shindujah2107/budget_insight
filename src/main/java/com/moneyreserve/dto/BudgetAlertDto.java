package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One alert the customer has been raised, and whether they have seen it.
 *
 * <p>The amounts are what was true when the threshold was crossed, not what is true now. A customer
 * who has since raised the cap, or moved a transaction out of the category, will see an alert whose
 * figures no longer match the card — which is correct: the alert is a record of a moment, and the
 * summary is the live view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BudgetAlertDto {

  private UUID id;

  /** CATEGORY_APPROACHING, CATEGORY_EXCEEDED or BUDGET_EXCEEDED. */
  private String type;

  /** The budget period the crossing belongs to. */
  private LocalDate periodStart;

  /** Absent on BUDGET_EXCEEDED, which is about the overall target rather than one category. */
  private String categoryCode;

  private String categoryName;

  /** The percentage that fired, on an APPROACHING alert. Absent on an EXCEEDED one. */
  private Short thresholdPct;

  private String amount;
  private String limit;
  private String currency;

  private OffsetDateTime raisedAt;

  /** Present once the customer has dismissed it. Never cleared. */
  private OffsetDateTime acknowledgedAt;
}
