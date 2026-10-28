package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.dto.FormatSampleBatchRequest;
import com.moneyreserve.dto.FormatSampleBatchResponse;
import com.moneyreserve.dto.UnmatchedStatsRequest;
import com.moneyreserve.dto.UnmatchedStatsResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.FormatDiscoveryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SMS format discovery: what a handset reports about bank messages no template could read.
 *
 * <p>Two routes, one per phase, each behind its own release flag:
 *
 * <ul>
 *   <li>{@code POST /formats/unmatched-stats} (Phase 1) — how many such messages each sender
 *       produced on a day. Counts only.
 *   <li>{@code POST /formats/samples} (Phase 2) — the <em>shape</em> of such a message: vocabulary
 *       word ids and typed placeholders, never its text ({@code SkeletonGrammar}).
 * </ul>
 *
 * <p>The message itself never leaves the phone. It waits there, encrypted, until a template that
 * reads it is published and synced; then it goes through ordinary ingest like any other.
 *
 * <p>Guards in the siblings' order: Insights off → 404; no caller → 401; this phase off → 501;
 * the phase's secret not configured → 501 (the device keeps its queue); then consent, inside the
 * service, exactly as ingest does it.
 */
@Slf4j
@Tag(
    name = "Budget Insights — SMS format discovery",
    description = "Counts and shapes of bank SMS that no template could read; never message text")
@RestController
@RequestMapping("/api/insights/formats")
@RequiredArgsConstructor
@Validated
public class InsightsFormatDiscoveryController {

  private final InsightsProperties properties;
  private final FormatDiscoveryService discovery;

  @Operation(summary = "Report daily counts of money SMS that no template could read (Phase 1)")
  @PostMapping("/unmatched-stats")
  public ResponseEntity<?> unmatchedStats(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody UnmatchedStatsRequest request) {
    ResponseEntity<?> unavailable =
        guard(
            caller,
            properties.getFormatDiscovery().isEnabled(),
            properties.hasDeviceHashSalt(),
            "the device-hash salt");
    if (unavailable != null) {
      return unavailable;
    }
    requireWithin(request.getStats(), properties.getFormatDiscovery().getMaxStatsPerRequest(), "counts");
    return ResponseEntity.ok(
        ApiResponse.<UnmatchedStatsResponse>builder()
            .success(true)
            .data(discovery.recordStats(caller, request))
            .build());
  }

  @Operation(summary = "Report the shapes of SMS that no template could read (Phase 2)")
  @PostMapping("/samples")
  public ResponseEntity<?> samples(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody FormatSampleBatchRequest request) {
    ResponseEntity<?> unavailable =
        guard(
            caller,
            properties.getFormatDiscovery().isEnabled()
                && properties.getFormatDiscovery().isSamplesEnabled(),
            properties.getFormatDiscovery().hasUserHashKey(),
            "the format-discovery user-hash key");
    if (unavailable != null) {
      return unavailable;
    }
    requireWithin(
        request.getSamples(), properties.getFormatDiscovery().getMaxSamplesPerRequest(), "samples");
    return ResponseEntity.ok(
        ApiResponse.<FormatSampleBatchResponse>builder()
            .success(true)
            .data(discovery.recordSamples(caller, request))
            .build());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /** 400 rather than 429 for an oversized batch, for the reason the ingest controller gives. */
  private static void requireWithin(List<?> items, int max, String what) {
    if (max > 0 && items != null && items.size() > max) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_FORMAT_BATCH_INVALID, "Please send at most " + max + " " + what + " at a time.");
    }
  }

  private ResponseEntity<?> guard(
      Caller caller, boolean phaseOn, boolean configured, String prerequisite) {
    if (!properties.isEnabled()) {
      log.info("SMS format discovery request refused: Budget Insights is switched off (404)");
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("Budget Insights is not available.")
                  .build());
    }
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
    if (!phaseOn) {
      log.info("SMS format discovery request refused: this phase is not enabled here (501)");
      return notServing();
    }
    if (!configured) {
      // 501 and not 500: it tells the device to hold its queue, which becomes sendable the moment
      // the secret is set, with no client change.
      log.error("SMS format discovery unavailable: {} is not configured (501)", prerequisite);
      return notServing();
    }
    return null;
  }

  private static ResponseEntity<?> notServing() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(
            ApiResponse.<Void>builder()
                .success(false)
                .message("This part of Budget Insights is not yet serving live data.")
                .build());
  }
}
