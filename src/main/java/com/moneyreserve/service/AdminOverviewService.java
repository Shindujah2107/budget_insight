package com.moneyreserve.service;

import com.moneyreserve.dto.AdminOverview;
import java.time.LocalDate;
import java.util.UUID;

/** Admin: the Overview screen. Super Admin only. */
public interface AdminOverviewService {

  /**
   * @param from inclusive; defaults to 29 days before {@code to}
   * @param to inclusive; defaults to today in the service zone
   */
  AdminOverview overview(LocalDate from, LocalDate to);
}
