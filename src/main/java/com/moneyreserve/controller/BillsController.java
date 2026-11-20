package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.dto.CreateBillRequest;
import com.moneyreserve.dto.UpdateBillRequest;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.service.BillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bills radar: the signed-in customer's recurring bills. Owner-scoped like every Insights route:
 * the customer comes from the access token, never from the request. 404 while Budget Insights is
 * off, 501 while the bills release gate is off.
 */
@Slf4j
@RestController
@RequestMapping("/api/insights/bills")
@Tag(name = "Bills", description = "Recurring bills found in the customer's own payments")
@RequiredArgsConstructor
@Validated
public class BillsController {

  private final InsightsProperties properties;
  private final BillService bills;

  @Operation(summary = "The customer's bills (detected and added by hand) and their monthly total")
  @GetMapping
  public ResponseEntity<?> list(@AuthenticationPrincipal Caller caller) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.list(caller));
  }

  @Operation(summary = "One bill and its recent payments")
  @GetMapping("/{billId}")
  public ResponseEntity<?> detail(@AuthenticationPrincipal Caller caller, @PathVariable UUID billId) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.detail(caller, billId));
  }

  @Operation(summary = "Add a bill by hand")
  @PostMapping
  public ResponseEntity<?> create(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody CreateBillRequest request) {
    ResponseEntity<?> refused = guard(caller);
    if (refused != null) {
      return refused;
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.builder().success(true).data(bills.create(caller, request)).build());
  }

  @Operation(summary = "Rename, confirm, mark not a bill, or change reminder days (and, for a bill added by hand, amount and dates)")
  @PatchMapping("/{billId}")
  public ResponseEntity<?> update(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID billId,
      @Valid @RequestBody UpdateBillRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.update(caller, billId, request));
  }

  @Operation(summary = "Mark this cycle of a bill paid: records the payment once, or pairs it with the bank SMS")
  @PostMapping("/{billId}/paid")
  public ResponseEntity<?> paid(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID billId,
      @Valid @RequestBody com.moneyreserve.dto.MarkBillPaidRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.markPaid(caller, billId, request));
  }

  @Operation(summary = "A suggested recurring payment and the payments it was found from")
  @GetMapping("/{billId}/suggestion")
  public ResponseEntity<?> suggestion(@AuthenticationPrincipal Caller caller, @PathVariable UUID billId) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.suggestion(caller, billId));
  }

  @Operation(summary = "Track a suggested recurring payment with the customer's own amount and dates")
  @PostMapping("/{billId}/track")
  public ResponseEntity<?> track(
      @AuthenticationPrincipal Caller caller, @PathVariable UUID billId, @Valid @RequestBody CreateBillRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.track(caller, billId, request));
  }

  @Operation(summary = "Yes, this bank payment is my bill payment: the SMS becomes the payment recorded for the bill")
  @PostMapping("/{billId}/match")
  public ResponseEntity<?> match(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID billId,
      @Valid @RequestBody com.moneyreserve.dto.BillSmsMatchRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.matchSms(caller, billId, request));
  }

  @Operation(summary = "Undo a match: the bank SMS is its own transaction again; the bill keeps the customer's payment")
  @PostMapping("/{billId}/unmatch")
  public ResponseEntity<?> unmatch(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID billId,
      @Valid @RequestBody com.moneyreserve.dto.BillSmsMatchRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.unmatchSms(caller, billId, request));
  }

  @Operation(summary = "\"I didn't pay it\" for a cycle left as Payment not confirmed")
  @PostMapping("/{billId}/cycles/{cycleId}/skip")
  public ResponseEntity<?> skip(
      @AuthenticationPrincipal Caller caller, @PathVariable UUID billId, @PathVariable UUID cycleId) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.skipCycle(caller, billId, cycleId));
  }

  @Operation(summary = "Remove a bill added by hand, or mark a found one not a bill")
  @DeleteMapping("/{billId}")
  public ResponseEntity<?> delete(@AuthenticationPrincipal Caller caller, @PathVariable UUID billId) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(bills.delete(caller, billId));
  }

  private ResponseEntity<?> guard(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiResponse.<Void>builder().success(false).message("Budget Insights is not available.").build());
    }
    if (!properties.getBills().isEnabled()) {
      log.info("Bills request refused: the bills release gate is off (501)");
      return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("Bills are not yet available here.")
                  .build());
    }
    return null;
  }

  private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).data(data).build());
  }
}
