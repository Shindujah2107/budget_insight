package com.moipay.budgetinsights.controller;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.dto.BudgetAlertDto;
import com.moipay.budgetinsights.exception.CallerUnidentifiedException;
import com.moipay.budgetinsights.service.BudgetAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget threshold alerts (BI-25, R6).
 *
 * <p><b>Read-and-dismiss only.</b> There is no route here that raises an alert. Raising happens on
 * the write paths, inside the transaction that recorded the spend which crossed the threshold — a
 * client that could ask for an alert to be raised could raise one that never happened.
 *
 * <p><b>In-app only.</b> §24 requires that crossing a limit raises an in-app alert and no external
 * message. Nothing behind these routes sends SMS, push or email, and there is no code path from
 * here to one.
 *
 * <p>Ownership comes from the gateway principal, as everywhere else. Neither route accepts a user
 * identifier, and the acknowledge route resolves the alert by id <em>and</em> owner, so another
 * customer's alert id is a 404 rather than a 403 — it does not confirm the row exists.
 */
@Slf4j
@Tag(name = "Budget Insights — Alerts", description = "Budget and category threshold alerts")
@RestController
@RequestMapping("/api/insights/alerts")
@RequiredArgsConstructor
@Validated
public class InsightsAlertController {

  private final InsightsProperties properties;
  private final BudgetAlertService alerts;

  @Operation(summary = "Alerts raised for the period containing the given date, newest first")
  @GetMapping
  public ResponseEntity<?> list(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

    ResponseEntity<?> unavailable = guard(caller);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.ok(
        ApiResponse.<BudgetAlertDto>builder()
            .success(true)
            .items(alerts.forPeriod(caller.userId(), on))
            .build());
  }

  /**
   * Marks one alert as seen.
   *
   * <p>POST rather than PATCH: this sets a timestamp that is never unset and carries no body, so
   * there is no partial update to express. Acknowledging twice is not an error and does not move
   * the timestamp — when the customer first saw it is the fact worth keeping.
   */
  @Operation(summary = "Mark an alert as seen")
  @PostMapping("/{id}/acknowledge")
  public ResponseEntity<?> acknowledge(
      @AuthenticationPrincipal Caller caller, @PathVariable UUID id) {

    ResponseEntity<?> unavailable = guard(caller);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.ok(
        ApiResponse.<BudgetAlertDto>builder()
            .success(true)
            .data(alerts.acknowledge(caller, id))
            .build());
  }

  // ---------------------------------------------------------------------------------

  /**
   * Feature switch, caller identity, then the release flag — the order the sibling controllers use.
   *
   * <p>No tenant check: neither route writes a row that carries one. Acknowledging updates a row
   * that already has its tenant from when it was raised.
   */
  private ResponseEntity<?> guard(Caller caller) {
    if (!properties.isEnabled()) {
      log.info("Budget alerts request refused: Budget Insights is switched off (404)");
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("Budget Insights is not available.")
                  .build());
    }
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException(
          "This endpoint needs a customer identity from the gateway.");
    }
    if (!properties.getAlerts().isEnabled()) {
      log.info("Budget alerts request refused: this feature is not enabled here (501)");
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("This part of Budget Insights is not yet serving live data.")
                  .build());
    }
    return null;
  }
}
