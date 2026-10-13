package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

/**
 * The customer's monthly spending target.
 *
 * <p>This is a figure the customer chooses. It is not derived from, and does not represent, any
 * account or card balance.
 *
 * <p>The period bounds are stored rather than derived from a calendar month, so a non-calendar
 * budget cycle can be introduced later as configuration rather than a migration.
 */
@Entity
@Table(
    name = "bi_budget",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_budget_user_period",
          columnNames = {"user_id", "period_start"})
    },
    indexes = {@Index(name = "ix_bi_budget_user_period", columnList = "user_id, period_start")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBudget {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "period_start", nullable = false)
  private LocalDate periodStart;

  @Column(name = "period_end", nullable = false)
  private LocalDate periodEnd;

  @Column(name = "overall_limit", nullable = false, precision = 19, scale = 2)
  private BigDecimal overallLimit;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /** Set when the previous period's target was carried forward rather than set fresh. */
  @Column(name = "rolled_from_id")
  private UUID rolledFromId;

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
    if (this.currency == null) {
      this.currency = "LKR";
    }
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
