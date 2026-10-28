package com.moneyreserve.config;

/**
 * Who may use the Budget Insights admin API, and where it lives.
 *
 * <p>Super Admin only: users whose number is listed in {@code moneyreserve.auth.super-admin-phones}.
 */
public final class AdminRoles {

  /** The role the admin API requires ({@link com.moneyreserve.domain.AppRole#SUPER_ADMIN}). */
  public static final String SUPER_ADMIN = "SUPER_ADMIN";

  /** Every admin route lives under this path (below the servlet context path). */
  public static final String BASE_PATH = "/api/insights/admin";

  private AdminRoles() {}
}
