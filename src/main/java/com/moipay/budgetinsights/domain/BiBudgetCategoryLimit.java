package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/** Optional per-category cap within a budget period. Drives the in-app category alert. */
@Entity
@Table(
    name = "bi_budget_category_limit",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_bcl_budget_cat",
          columnNames = {"budget_id", "category_id"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBudgetCategoryLimit {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "budget_id", nullable = false)
  private UUID budgetId;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "limit_amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal limitAmount;

  /**
   * Percentage of the limit at which the approaching-limit alert fires. Null (since V1.9.0) when the
   * customer did not choose one: the budget policy's default applies, and follows its changes.
   */
  @Column(name = "alert_threshold_pct")
  private Short alertThresholdPct;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    // A null threshold is deliberate since V1.9.0: the cap follows the budget policy's default.
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
