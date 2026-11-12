package com.moneyreserve.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The cash wallet as the app shows it. {@code balance} is the ledger's, never a sum of a page of
 * transactions; {@code version} changes on every financial or review change and is what a
 * reconciliation must name. Nulls are sent as null: the app checks {@code reviewDueAt} is present.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CashWalletDto {
  private String currency;
  private boolean active;
  private String activatedAt;
  private String balance;
  private String version;
  private List<TransactionDto> recentActivity;
  /** The oldest unanswered withdrawal check, or null. */
  private String reviewDueAt;
}
