package com.moipay.budgetinsights.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Save the budget policy as a draft. Every field is required (the screen always sends the whole
 * policy). Needs + Wants + Savings must add up to 100; that cross-field rule is checked by the
 * service and again at publish.
 */
public record AdminBudgetPolicyInput(
    @NotNull @Min(0) @Max(100) Integer needsPct,
    @NotNull @Min(0) @Max(100) Integer wantsPct,
    @NotNull @Min(0) @Max(100) Integer savingsPct,
    @NotNull @Min(1) @Max(12) Integer trailingMonths,
    @NotNull @Min(50) @Max(95) Integer defaultAlertPct,
    @NotNull @Min(0) @Max(25) Integer paceTolerancePct,
    @NotNull Boolean alertCategoryApproaching,
    @NotNull Boolean alertCategoryExceeded,
    @NotNull Boolean alertBudgetExceeded) {

  public AdminBudgetPolicyState toState() {
    return new AdminBudgetPolicyState(
        needsPct,
        wantsPct,
        savingsPct,
        trailingMonths,
        defaultAlertPct,
        paceTolerancePct,
        alertCategoryApproaching,
        alertCategoryExceeded,
        alertBudgetExceeded);
  }
}
