package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * The record that a threshold was crossed and the customer was told (BI-25).
 *
 * <p>§24 requires that crossing a category limit raises <b>exactly one</b> in-app alert. Exactly
 * once is a statement about history rather than about the present, so it needs a row: the summary
 * can say a category is over its cap on every read, but only this table knows whether that has
 * already been said.
 *
 * <p>{@link #categoryId} is null for an alert about the overall monthly target. Two partial unique
 * indexes enforce the one-per-period rule for both shapes — a single constraint could not, because
 * in PostgreSQL two NULLs do not collide and every overall-budget alert would insert again.
 *
 * <p>The amounts are copied in rather than read back live. A customer who later raises their cap,
 * or re-categorises a transaction out of the category, would otherwise see an alert whose numbers
 * reconcile with nothing. The alert is evidence of a moment; the summary is the live view.
 */
@Entity
@Table(name = "bi_budget_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBudgetAlert {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  /** The budget period this crossing belongs to, matching {@code bi_budget.period_start}. */
  @Column(name = "period_start", nullable = false)
  private LocalDate periodStart;

  /** Null for the overall monthly target. */
  @Column(name = "category_id")
  private UUID categoryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "alert_type", nullable = false, length = 24)
  private BudgetAlertType alertType;

  /** The percentage that fired, on an APPROACHING alert. Null on an EXCEEDED one. */
  @Column(name = "threshold_pct")
  private Short thresholdPct;

  @Column(name = "amount_at_raise", nullable = false)
  private BigDecimal amountAtRaise;

  @Column(name = "limit_at_raise", nullable = false)
  private BigDecimal limitAtRaise;

  @Column(name = "raised_at", nullable = false)
  private OffsetDateTime raisedAt;

  /**
   * Set when the customer has seen it, and never unset.
   *
   * <p>An alert that can be un-acknowledged is an alert that can be shown twice, which is the thing
   * this table exists to prevent.
   */
  @Column(name = "acknowledged_at")
  private OffsetDateTime acknowledgedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.raisedAt == null) {
      this.raisedAt = OffsetDateTime.now();
    }
  }

  public boolean isAcknowledged() {
    return acknowledgedAt != null;
  }
}
