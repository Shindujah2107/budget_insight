package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.dto.CreateTransactionRequest;
import com.moneyreserve.dto.TransactionMutationResponse;
import com.moneyreserve.dto.UpdateTransactionRequest;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.service.InsightsTransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adding, correcting and removing transactions on the Insights screen.
 *
 * <p>Split from the read controller because the two have genuinely different risk
 * profiles, and keeping them apart means a future access rule can be applied to writes alone
 * without disturbing reads.
 *
 * <p>Every response carries the recomputed breakdown, so the screen never has to make a second call
 * to find out what its own write did. See {@link TransactionMutationResponse}.
 *
 * <p>These endpoints are not stubbed. A fixture that accepts a write and forgets it is worse than
 * no endpoint at all: the client integrates against behaviour that does not exist and the defect
 * surfaces only once the real implementation lands.
 *
 * <p>The R1 manual release gate is <b>not</b> in these methods. It is
 * {@link com.moneyreserve.config.ManualEntryGate}, an interceptor, because a gate checked
 * here runs after {@code @Valid} has already rejected a malformed body with 400 — so a withdrawn
 * feature answered 400 rather than 501 for exactly the requests a client is most likely to send
 * while it is withdrawn. The master switch's 404 is still decided here.
 */
@Tag(name = "Budget Insights — Transactions", description = "Manual entry and correction")
@RestController
@RequestMapping("/api/insights/transactions")
@RequiredArgsConstructor
@Validated
public class InsightsTransactionController {

  private final InsightsProperties properties;
  private final InsightsTransactionService service;

  @Operation(summary = "Add a transaction by hand, including cash, and get the updated breakdown")
  @PostMapping
  public ResponseEntity<?> create(
      @AuthenticationPrincipal Caller caller,
      @Valid @RequestBody CreateTransactionRequest request,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    requireCaller(caller);
    TransactionMutationResponse body = service.create(caller, request, from, to);
    HttpStatus status =
        Boolean.TRUE.equals(body.getDuplicate()) ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status)
        .body(ApiResponse.<TransactionMutationResponse>builder().success(true).data(body).build());
  }

  @Operation(summary = "Correct a transaction; returns the transaction and the updated breakdown")
  @PatchMapping("/{id}")
  public ResponseEntity<?> update(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateTransactionRequest request,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    requireCaller(caller);
    return ok(service.update(caller, id, request, from, to));
  }

  @Operation(summary = "Remove a transaction; returns the updated breakdown")
  @DeleteMapping("/{id}")
  public ResponseEntity<?> delete(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    requireCaller(caller);
    return ok(service.delete(caller, id, from, to));
  }

  // ---------------------------------------------------------------------------------

  private void requireCaller(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
  }

  /** Feature off is 404: the Insights tab is meant to look absent, not broken. */
  private ResponseEntity<?> featureGuard() {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("Budget Insights is not available.")
                  .build());
    }
    return null;
  }

  private ResponseEntity<ApiResponse<TransactionMutationResponse>> ok(
      TransactionMutationResponse body) {
    return ResponseEntity.ok(
        ApiResponse.<TransactionMutationResponse>builder().success(true).data(body).build());
  }
}
