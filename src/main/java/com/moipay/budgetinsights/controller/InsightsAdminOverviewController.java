package com.moipay.budgetinsights.controller;

import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.AdminRoles;
import com.moipay.budgetinsights.dto.AdminOverview;
import com.moipay.budgetinsights.service.AdminOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget Insights admin — Overview (screen 01): is Budget Insights working for customers?
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches in {@code AdminAccessGuard}. Counts
 * only — no amount, customer identifier, account number or SMS text is returned.
 */
@Tag(name = "Budget Insights admin — overview", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH)
@RequiredArgsConstructor
public class InsightsAdminOverviewController {

  private final AdminOverviewService overview;

  @Operation(
      summary = "KPIs, transactions per day by source, category and bank distribution, "
          + "reference versions on handsets")
  @GetMapping("/overview")
  public ResponseEntity<ApiResponse<AdminOverview>> overview(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID tenantId) {
    return AdminHttp.ok(overview.overview(from, to, tenantId));
  }
}
