package com.moipay.budgetinsights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The budget policy (V1.9.0): one row, id 1. Written only by a publish from the Admin Dashboard;
 * read through {@code BudgetPolicyProvider}.
 */
@Entity
@Table(name = "bi_budget_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBudgetPolicy {

  public static final short ID = 1;

  @Id
  @Column(name = "id", nullable = false)
  private Short id;

  @Column(name = "needs_pct", nullable = false)
  private Short needsPct;

  @Column(name = "wants_pct", nullable = false)
  private Short wantsPct;

  @Column(name = "savings_pct", nullable = false)
  private Short savingsPct;

  @Column(name = "trailing_months", nullable = false)
  private Short trailingMonths;

  @Column(name = "default_alert_pct", nullable = false)
  private Short defaultAlertPct;

  @Column(name = "pace_tolerance_pct", nullable = false)
  private Short paceTolerancePct;

  @Column(name = "alert_cat_approaching_on", nullable = false)
  private Boolean alertCategoryApproaching;

  @Column(name = "alert_cat_exceeded_on", nullable = false)
  private Boolean alertCategoryExceeded;

  @Column(name = "alert_budget_exceeded_on", nullable = false)
  private Boolean alertBudgetExceeded;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
