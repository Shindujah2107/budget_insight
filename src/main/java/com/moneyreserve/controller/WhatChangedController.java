package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.impl.WhatChangedServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * "What changed?" (Premium Insights, V1.28.0). The comparison is refused on the server unless the
 * global switch is on (MR.WCH.E.001) and the customer is entitled to Premium (MR.WCH.E.002); the
 * availability check is open so the app can show the right state. Each period's transactions open
 * through {@code GET /api/insights/breakdown/{categoryId}/transactions} with the filters the
 * comparison returns (ordinary history, not gated).
 */
@RestController
@RequestMapping("/api/insights/what-changed")
@Tag(name = "What changed?", description = "Premium: spending per category against the period before")
@RequiredArgsConstructor
public class WhatChangedController {

  private final InsightsProperties properties;
  private final WhatChangedServiceImpl whatChanged;

  @Operation(summary = "Whether \"What changed?\" is available to this customer, and if not why")
  @GetMapping("/availability")
  public ResponseEntity<?> availability(@AuthenticationPrincipal Caller caller) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(whatChanged.availability(caller.userId()));
  }

  @Operation(summary = "The three largest category changes against the period before (Premium)")
  @GetMapping
  public ResponseEntity<?> compare(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) String month,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    if (off != null) {
      return off;
    }
    return ok(whatChanged.compare(caller.userId(), parse(month), accountIds));
  }

  private static YearMonth parse(String month) {
    if (month == null || month.isBlank()) {
      return null;
    }
    try {
      return YearMonth.parse(month.trim());
    } catch (DateTimeParseException e) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_WHAT_CHANGED_PERIOD);
    }
  }

  private ResponseEntity<?> insightsOff() {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiResponse.<Void>builder().success(false).message("Budget Insights is not available.").build());
    }
    return null;
  }

  private static void requireCaller(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
  }

  private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).data(data).build());
  }
}
