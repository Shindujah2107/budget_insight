package com.moneyreserve.controller;

import lombok.extern.slf4j.Slf4j;
import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.dto.IngestBatchRequest;
import com.moneyreserve.dto.IngestBatchResponse;
import com.moneyreserve.dto.ReferenceSyncResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.IngestService;
import com.moneyreserve.service.ReferenceSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SMS ingest, and the reference data that makes it possible (R4).
 *
 * <p>Two routes, and between them they are the whole of the SMS path this service sees. Everything
 * upstream of here happens on the handset: the broadcast receiver, the allowlist check, the OTP
 * filter, the parser, and the local queue. What arrives at this controller is fields — never a
 * message.
 *
 * <p><b>Why the batch can succeed with rejected messages in it.</b> The device's queue is the unit
 * it manages, and the per-message result set is how it clears exactly what landed. Failing the
 * request because one row was malformed would make a device resend its whole queue, including the
 * row that will fail again. Two things do refuse the batch outright, and both are properties of the
 * batch rather than of a message: consent that is not in force, and a device over its rate limit.
 *
 * <p><b>Consent is enforced here and only here.</b> The sibling controllers do not check it,
 * because Budget Insights works fully without it — manual entry, the breakdown, the budget. Consent
 * governs reading the customer's bank messages, which is what these two routes do.
 */
@Slf4j
@Tag(name = "Budget Insights — Ingest", description = "SMS ingest and device reference sync")
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
@Validated
public class InsightsIngestController {

  private final InsightsProperties properties;
  private final IngestService ingest;
  private final ReferenceSyncService reference;

  @Operation(summary = "Ingest a batch of parsed bank messages from a device")
  @PostMapping("/messages/ingest")
  public ResponseEntity<?> ingest(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody IngestBatchRequest request) {

    ResponseEntity<?> unavailable = guard(caller);
    if (unavailable != null) {
      return unavailable;
    }
    requireBatchWithinBounds(request);
    return ResponseEntity.ok(
        ApiResponse.<IngestBatchResponse>builder()
            .success(true)
            .data(ingest.ingest(caller, request))
            .build());
  }

  /**
   * The sender allowlist and the parser templates the device reads messages with.
   *
   * <p>Behind the same consent gate as ingest. Handing a device the rules for reading bank messages
   * is not itself reading one, but it has no purpose except to enable it, and a customer who has
   * withdrawn consent should not find their handset still being told how to parse.
   */
  @Operation(summary = "Fetch the sender allowlist and parser templates for a device")
  @GetMapping("/reference/sync")
  public ResponseEntity<?> sync(
      @AuthenticationPrincipal Caller caller,
      // Bounded to match bi_device_sync_state.device_id (varchar 128) and the same field on the
      // ingest body. Unbounded, a longer value reached the INSERT and failed there, which is a 500
      // and a lost sync on the one route a handset calls to stay working.
      @RequestParam(required = false) @Size(max = 128, message = "The device identifier is too long.")
          String deviceId,
      @RequestParam(required = false) Integer templateVersion,
      // SMS format discovery: the vocabulary version the phone holds. Only phones that send it are
      // given the vocabulary, so the response to every older app is unchanged.
      @RequestParam(required = false) @Min(value = 0, message = "The vocabulary version is not valid.")
          Integer vocabularyVersion) {

    ResponseEntity<?> unavailable = guard(caller);
    if (unavailable != null) {
      return unavailable;
    }
    return ResponseEntity.ok(
        ApiResponse.<ReferenceSyncResponse>builder()
            .success(true)
            .data(reference.sync(caller, deviceId, templateVersion, vocabularyVersion))
            .build());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * Bounds the unit of work before any of it starts.
   *
   * <p>The batch is one transaction. A handset returning after months offline could otherwise hand
   * over a queue large enough to hold write locks for as long as it takes to insert, which is the
   * kind of outage that only ever happens to the customer with the most data.
   *
   * <p><b>This is a 400 and not a 429, deliberately.</b> A throttled device is told to send the
   * same batch again later, and that is the right answer when the device is simply going too fast.
   * An oversized batch will be exactly as oversized after any wait, so answering 429 would invite
   * a handset to retry an identical payload on a timer, for ever. A 400 says the request itself has
   * to change, and the message says how.
   */
  private void requireBatchWithinBounds(IngestBatchRequest request) {
    int max = properties.getIngest().getMaxBatchSize();
    if (max > 0 && request.getMessages() != null && request.getMessages().size() > max) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID,
          "Please send at most " + max + " messages at a time.");
    }
  }

  /** Feature switch, caller identity, then the release flag — the order the siblings use. */
  private ResponseEntity<?> guard(Caller caller) {
    if (!properties.isEnabled()) {
      log.info("SMS ingest request refused: Budget Insights is switched off (404)");
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
    if (!properties.getIngest().isEnabled()) {
      log.info("SMS ingest request refused: this feature is not enabled here (501)");
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
