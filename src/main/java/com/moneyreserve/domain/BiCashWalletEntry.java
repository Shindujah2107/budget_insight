package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One change to a cash wallet: a signed amount and the balance after it. Never updated except to
 * mark it reversed; a reversal is a new entry with the opposite amount.
 */
@Entity
@Table(name = "bi_cash_wallet_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiCashWalletEntry {

  public enum Kind {
    OPENING,
    WITHDRAWAL,
    EXPENSE,
    INCOME,
    BANK_TO_CASH,
    CASH_TO_BANK,
    CORRECTION,
    REVERSAL
  }

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "wallet_id", nullable = false)
  private UUID walletId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 24)
  private Kind kind;

  /** Signed: money into the wallet is positive. */
  @Column(name = "amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
  private BigDecimal balanceAfter;

  @Column(name = "revision", nullable = false)
  private long revision;

  @Column(name = "transaction_id")
  private UUID transactionId;

  @Column(name = "reverses_entry_id")
  private UUID reversesEntryId;

  /** On the entry taking back a withdrawal's cash after a bank reversal: that withdrawal (V1.21.1). */
  @Column(name = "linked_transaction_id")
  private UUID linkedTransactionId;

  @Column(name = "reversed", nullable = false)
  private boolean reversed;

  /** The reconciliation reason, when the entry came from one. */
  @Column(name = "reason", length = 24)
  private String reason;

  /** Corrections: the balance before and the amount the customer said they hold. */
  @Column(name = "previous_balance", precision = 19, scale = 2)
  private BigDecimal previousBalance;

  @Column(name = "stated_balance", precision = 19, scale = 2)
  private BigDecimal statedBalance;

  @Column(name = "actor", nullable = false, length = 64)
  private String actor;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
