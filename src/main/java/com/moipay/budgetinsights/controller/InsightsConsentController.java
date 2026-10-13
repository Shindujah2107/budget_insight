package com.moipay.budgetinsights.controller;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.dto.ConsentGrantRequest;
import com.moipay.budgetinsights.dto.ConsentResponse;
import com.moipay.budgetinsights.dto.DataErasureResponse;
import com.moipay.budgetinsights.dto.DataExportResponse;
import com.moipay.budgetinsights.exception.CallerUnidentifiedException;
import com.moipay.budgetinsights.service.ConsentService;
import com.moipay.budgetinsights.service.DataRightsService;
import com.moipay.budgetinsights.service.FormatDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consent, and the data rights that sit beside it.
 *
 * <p><b>Consent is not a gate on this screen.</b> Budget Insights works fully without it — manual
 * entry, the breakdown, the budget and every figure derived from them. Consent governs one thing:
 * reading the customer's bank messages. A customer who declines, or later withdraws, keeps the
 * whole feature and loses only automatic capture. That is the "degrade, never block" position, and
 * it is why none of the endpoints in the sibling controllers checks consent.
 *
 * <p><b>Nothing reads bank messages yet.</b> Ingest is R4, so today consent is recorded and
 * enforceable but has nothing to enforce against. The record is built first because a consent
 * history that begins on the day capture begins is a consent history with a hole in it.
 *
 * <p><b>Export and erasure are fulfilled here (BI-27, BI-28).</b> They answered 501 from R3 until
 * R7 rather than accepting a request and queueing it, because accepting a data-rights request that
 * nothing will fulfil is worse than not offering it — the customer would believe they had
 * exercised a right they had not. The shapes fixed then are the shapes served now.
 */
@Slf4j
@Tag(name = "Budget Insights — Consent & data control", description = "Consent, export and erasure")
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
@Validated
public class InsightsConsentController {

  private final InsightsProperties properties;
  private final ConsentService consent;
  private final DataRightsService dataRights;
  private final FormatDiscoveryService formatDiscovery;

  // ---------------------------------------------------------------------------------
  // Consent
  // ---------------------------------------------------------------------------------

  @Operation(summary = "Record consent to read bank messages, with its version and policy hash")
  @PostMapping("/consent")
  public ResponseEntity<?> grant(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody ConsentGrantRequest request) {

    ResponseEntity<?> unavailable = guard(caller, true);
    if (unavailable != null) {
      return unavailable;
    }
    return ok(consent.grant(caller, request));
  }

  @Operation(summary = "The customer's current consent position")
  @GetMapping("/consent")
  public ResponseEntity<?> current(@AuthenticationPrincipal Caller caller) {
    ResponseEntity<?> unavailable = guard(caller, false);
    if (unavailable != null) {
      return unavailable;
    }
    return ok(consent.current(caller));
  }

  @Operation(summary = "Withdraw consent; capture stops on the next request")
  @DeleteMapping("/consent")
  public ResponseEntity<?> withdraw(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) String channel) {

    ResponseEntity<?> unavailable = guard(caller, false);
    if (unavailable != null) {
      return unavailable;
    }
    ConsentResponse withdrawn = consent.withdraw(caller, channel);
    // SMS format discovery: the samples this customer's phone contributed go with their consent.
    // The withdrawal itself is already committed, so a failure here must not turn it into an
    // error the app would show: it is logged, and the daily retention pass sweeps every recent
    // withdrawal again (FormatDiscoveryMaintenance), so the samples still go within a day.
    try {
      formatDiscovery.forget(caller.userId());
    } catch (RuntimeException ex) {
      log.error(
          "Insights consent withdrawn userId={} but format samples were not removed now;"
              + " the daily retention pass will remove them",
          caller.userId(),
          ex);
    }
    return ok(withdrawn);
  }

  // ---------------------------------------------------------------------------------
  // Data rights — contract fixed, fulfilment in R7
  // ---------------------------------------------------------------------------------

  /**
   * Queue a full export of the customer's Budget Insights data.
   *
   * <p>Answers 501 until R7. Returning a job identifier for a job that nothing will run would tell
   * the customer their request is in progress when it is not, and a data-subject request recorded
   * as accepted but never fulfilled is a worse compliance position than one that was never offered.
   */
  @Operation(summary = "Request a full data export")
  @PostMapping("/data/export")
  public ResponseEntity<?> requestExport(@AuthenticationPrincipal Caller caller) {
    ResponseEntity<?> unavailable = guard(caller, false);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.accepted()
        .body(
            ApiResponse.<DataExportResponse>builder()
                .success(true)
                .data(dataRights.requestExport(caller))
                .build());
  }

  /**
   * Hands over a prepared export.
   *
   * <p>Returns the document as raw JSON rather than inside the usual {@link ApiResponse} envelope.
   * The envelope exists so a client can tell success from failure on a screen; this is a file the
   * customer downloads and keeps, and wrapping it would mean every reader had to unwrap their own
   * data to read it. Failures still come back enveloped, through the exception handler.
   */
  @Operation(summary = "Download a prepared export by its id")
  @GetMapping(value = "/data/export/{exportId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<?> downloadExport(
      @AuthenticationPrincipal Caller caller, @PathVariable UUID exportId) {

    ResponseEntity<?> unavailable = guard(caller, false);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            "attachment; filename=\"moipay-budget-insights-export.json\"")
        .body(dataRights.downloadExport(caller, exportId));
  }

  /**
   * Erase every Budget Insights row for the customer.
   *
   * <p>Answers 501 until R7, for the same reason as export, and more sharply: an erasure that is
   * acknowledged and not performed leaves the customer believing data is gone while it is not.
   */
  @Operation(summary = "Erase all Budget Insights data for the customer")
  @DeleteMapping("/data")
  public ResponseEntity<?> requestErasure(@AuthenticationPrincipal Caller caller) {
    ResponseEntity<?> unavailable = guard(caller, false);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.ok(
        ApiResponse.<DataErasureResponse>builder()
            .success(true)
            .data(dataRights.erase(caller))
            .build());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * Feature switch, release flag and caller identity, in that order.
   *
   * @param needsTenant true for operations that write a row — every consent record carries a
   *     tenant, so a caller without one cannot be recorded and must not be silently accepted
   */
  private ResponseEntity<?> guard(Caller caller, boolean needsTenant) {
    if (!properties.isEnabled()) {
      log.info("Consent request refused: Budget Insights is switched off (404)");
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
    if (needsTenant && caller.tenantId() == null) {
      throw new CallerUnidentifiedException(
          "Recording consent needs a tenant identity from the gateway.");
    }
    if (!properties.getConsent().isEnabled()) {
      return notImplemented();
    }
    return null;
  }

  private ResponseEntity<?> notImplemented() {
    log.info("Consent request refused: this feature is not enabled here (501)");
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(
            ApiResponse.<Void>builder()
                .success(false)
                .message("This part of Budget Insights is not yet serving live data.")
                .build());
  }

  private ResponseEntity<ApiResponse<ConsentResponse>> ok(ConsentResponse body) {
    return ResponseEntity.ok(
        ApiResponse.<ConsentResponse>builder().success(true).data(body).build());
  }
}
