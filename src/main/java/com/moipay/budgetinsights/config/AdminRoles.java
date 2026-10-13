package com.moipay.budgetinsights.config;

/**
 * Who may use the Budget Insights admin API, and where it lives.
 *
 * <p>Super Admin only (decision of 29 Sep 2026). {@code TENANT_ADMIN} must never be added: every
 * self-registered customer carries it.
 */
public final class AdminRoles {

  /** Role name as it arrives in {@code X-User-Roles}; Spring sees {@code ROLE_SUPER_ADMIN}. */
  public static final String SUPER_ADMIN = "SUPER_ADMIN";

  /** Every admin route lives under this path (below the servlet context path). */
  public static final String BASE_PATH = "/api/insights/admin";

  private AdminRoles() {}
}
