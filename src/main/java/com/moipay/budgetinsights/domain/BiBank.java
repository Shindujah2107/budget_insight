package com.moipay.budgetinsights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A bank Budget Insights supports: the list the Admin Dashboard offers when adding SMS senders and
 * parser templates, and the names the Overview shows.
 *
 * <p>Managed from the Admin Dashboard (V1.6.0); it replaced the bank list that used to sit in
 * application config. The code is the 4-digit CBSL bank code shared with core's {@code bank_list},
 * and it never changes: senders, templates and accounts carry it as text, not as a foreign key.
 * Changes apply immediately — a bank on its own reaches no handset; its senders and templates still
 * go through draft and publish.
 */
@Entity
@Table(name = "bi_bank")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBank {

  @Id
  @Column(name = "bank_code", nullable = false, length = 16)
  private String bankCode;

  @Column(name = "bank_name", nullable = false, length = 64)
  private String bankName;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  /**
   * True while it was created together with a sender draft that is not published yet. Publishing
   * clears it; discarding the draft removes the row if nothing else uses it (V1.7.0).
   */
  @Column(name = "is_pending", nullable = false)
  private Boolean isPending;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Staff id of the Super Admin who added it; null for banks seeded by the migration. */
  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;

  /** Rows built without a pending flag are plain, live banks. */
  @PrePersist
  void defaults() {
    if (isPending == null) {
      isPending = false;
    }
  }
}
