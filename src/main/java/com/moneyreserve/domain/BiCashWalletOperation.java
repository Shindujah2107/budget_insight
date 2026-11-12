package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** A reconciliation already applied, by the client's reference: retries return its outcome. */
@Entity
@Table(
    name = "bi_cash_wallet_operation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "ux_bi_cash_op_user_ref",
            columnNames = {"user_id", "client_ref"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiCashWalletOperation {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "client_ref", nullable = false, length = 96)
  private String clientRef;

  /** SHA-256 of the request's fields: the same reference with a different request is refused. */
  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "wallet_id", nullable = false)
  private UUID walletId;

  @Column(name = "revision_after", nullable = false)
  private long revisionAfter;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
