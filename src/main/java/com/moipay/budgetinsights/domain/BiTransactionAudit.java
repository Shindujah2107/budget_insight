package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Append-only record of everything the customer changed by hand on a transaction. */
@Entity
@Table(
    name = "bi_transaction_audit",
    indexes = {@Index(name = "ix_bi_txn_audit_txn", columnList = "transaction_id, created_at")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiTransactionAudit {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "transaction_id", nullable = false)
  private UUID transactionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 16)
  private AuditAction action;

  @Column(name = "old_category_id")
  private UUID oldCategoryId;

  @Column(name = "new_category_id")
  private UUID newCategoryId;

  @Column(name = "actor", nullable = false, length = 64)
  private String actor;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    this.createdAt = OffsetDateTime.now();
  }
}
