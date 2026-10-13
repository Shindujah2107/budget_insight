package com.moipay.budgetinsights.controller;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.dto.ComplianceEventBatchRequest;
import com.moipay.budgetinsights.dto.ComplianceEventBatchResponse;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.CallerUnidentifiedException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.service.ClientComplianceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-side compliance reporting (BI-36).
 *
 * <p>One route. The handset's OTP and PIN filter is the one that catches most of what it is meant
 * to catch, because a well-behaved client never posts a message the filter stopped — which meant
 * that until this route existed, the compliance record only ever saw the detections the
 * <em>server</em> made, and systematically under-counted the control it exists to evidence.
 *
 * <p><b>What arrives here is an assertion that something was destroyed, not the thing.</b> There is
 * no field on the request capable of carrying the message, the code, the sender or an amount, and
 * {@code InsightsDtoArchitectureTest} fails the build if one appears. The client reference is
 * hashed before it is stored, so even the one client-controlled string cannot become a place to put
 * a code.
 *
 * <p><b>There is no release flag.</b> The capabilities that carry one carry it because they are
 * things a customer sees and a rollback could spare them. This is the evidence that a filter
 * already running on every handset is working, so a flag would only ever buy the option of running
 * the control and not recording it — the state this route exists to end. What can withhold it is
 * the device hash salt, and that is a configuration state rather than a release decision.
 *
 * <p><b>Consent is enforced here, as it is on ingest.</b> A handset whose owner has withdrawn
 * consent should have stopped reading bank messages altogether, so it should have nothing to
 * report; a route that accepted reports anyway would be accepting evidence that the withdrawal was
 * not honoured and filing it as routine.
 */
@Slf4j
@Tag(
    name = "Budget Insights — Compliance",
    description = "Device-side OTP and PIN detections, recorded as compliance incidents")
@RestController
@RequestMapping("/api/insights/compliance")
@RequiredArgsConstructor
@Validated
public class InsightsComplianceController {

  private final InsightsProperties properties;
  private final ClientComplianceService clientCompliance;

  @Operation(summary = "Report OTP or PIN messages the device detected and discarded")
  @PostMapping("/events")
  public ResponseEntity<?> report(
      @AuthenticationPrincipal Caller caller,
      @Valid @RequestBody ComplianceEventBatchRequest request) {

    // A compliance row carries a tenant and nothing else that identifies anyone. A caller the
    // gateway could not attribute must not have an incident recorded against a null tenant and
    // discovered, unattributable, by whoever reads this table next.
    ResponseEntity<?> unavailable = guard(caller);
    if (unavailable != null) {
      return unavailable;
    }
    requireBatchWithinBounds(request);
    return ResponseEntity.ok(
        ApiResponse.<ComplianceEventBatchResponse>builder()
            .success(true)
            .data(clientCompliance.record(caller, request))
            .build());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * Bounds the unit of work before any of it starts.
   *
   * <p>A 400 rather than a 429, for the reason the ingest controller gives: an oversized batch is
   * exactly as oversized after any wait, so a 429 would invite a handset to retry an identical
   * payload for ever. A 400 says the request itself has to change, and the message says how.
   */
  private void requireBatchWithinBounds(ComplianceEventBatchRequest request) {
    int max = properties.getCompliance().getMaxBatchSize();
    if (max > 0 && request.getEvents() != null && request.getEvents().size() > max) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_COMPLIANCE_BATCH_INVALID,
          "Please send at most " + max + " events at a time.");
    }
  }

  /**
   * Feature switch, caller identity, then the salt — the siblings' order, with their release flag
   * replaced by the only prerequisite this route has.
   */
  private ResponseEntity<?> guard(Caller caller) {
    if (!properties.isEnabled()) {
      log.info("Device compliance reporting request refused: Budget Insights is switched off (404)");
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
    if (caller.tenantId() == null) {
      throw new CallerUnidentifiedException(
          "Recording a compliance incident needs a tenant identity from the gateway.");
    }
    // The one thing that can withhold this route, and it is a configuration state rather than a
    // release decision. Without a salt the client reference cannot be stored as a digest a client
    // could not reverse, and this is the table that is never purged. 501 rather than 500 because
    // it is the answer that tells a device to hold its queue instead of discarding it: the
    // incidents are unrecoverable once dropped, and they become recordable the moment the salt is
    // set, with no client change and no release.
    if (!properties.hasDeviceHashSalt()) {
      log.error(
          "Device compliance reporting unavailable: the device-hash salt is not configured (501);"
              + " devices will hold their incident queue until it is set");
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
