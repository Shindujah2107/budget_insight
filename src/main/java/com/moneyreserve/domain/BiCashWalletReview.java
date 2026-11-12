package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** The balance check a credited withdrawal is owed, due seven days after it. */
@Entity
@Table(
    name = "bi_cash_wallet_review",
    uniqueConstraints = @UniqueConstraint(name = "ux_bi_cash_review_entry", columnNames = "entry_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiCashWalletReview {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "wallet_id", nullable = false)
  private UUID walletId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Column(name = "due_at", nullable = false)
  private OffsetDateTime dueAt;

  @Column(name = "resolved_at")
  private OffsetDateTime resolvedAt;

  @Column(name = "resolved_revision")
  private Long resolvedRevision;
}
