package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminOverview;
import java.time.LocalDate;
import java.util.UUID;

/** Admin: the Overview screen. Super Admin only; all tenants unless one is given. */
public interface AdminOverviewService {

  /**
   * @param from inclusive; defaults to 29 days before {@code to}
   * @param to inclusive; defaults to today in the service zone
   * @param tenantId optional tenant filter
   */
  AdminOverview overview(LocalDate from, LocalDate to, UUID tenantId);
}
