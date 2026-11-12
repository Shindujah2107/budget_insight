package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * A customer's cash wallet in one currency (V1.21.0). {@code balance} is kept in step with the
 * entries under a row lock; {@code revision} moves on every financial or review change and is the
 * version a reconciliation must name.
 */
@Entity
@Table(
    name = "bi_cash_wallet",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ux_bi_cash_wallet_user_currency",
            columnNames = {"user_id", "currency"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiCashWallet {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /** Null until the customer states their opening cash. */
  @Column(name = "activated_at")
  private OffsetDateTime activatedAt;

  @Column(name = "balance", nullable = false, precision = 19, scale = 2)
  private BigDecimal balance;

  @Column(name = "revision", nullable = false)
  private long revision;

  /** The opening count already included that day's ATM withdrawals (V1.21.1). */
  @Column(name = "opening_covers_day", nullable = false)
  private boolean openingCoversDay;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public boolean isActive() {
    return activatedAt != null;
  }
}
