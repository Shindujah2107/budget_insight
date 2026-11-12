package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.dto.CashWalletReconcileRequest;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.service.impl.CashWalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * The signed-in customer's cash wallet. Owner-scoped like every Insights route: the customer comes
 * from the access token, never from the request. 404 while Budget Insights is off.
 */
@RestController
@RequestMapping("/api/insights/cash-wallet")
@Tag(name = "Cash wallet", description = "Cash in hand: balance, activity and balance checks")
@RequiredArgsConstructor
@Validated
public class CashWalletController {

  private final InsightsProperties properties;
  private final CashWalletService wallet;

  @Operation(summary = "Balance, version, recent activity and the next balance check")
  @GetMapping
  public ResponseEntity<?> get(
      @AuthenticationPrincipal Caller caller, @RequestParam(required = false) String currency) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(wallet.get(caller.userId(), currency));
  }

  @Operation(summary = "The wallet's activity, newest first, a page at a time")
  @GetMapping("/activity")
  public ResponseEntity<?> activity(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) String currency,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(wallet.activity(caller.userId(), currency, page, size));
  }

  @Operation(summary = "Open the wallet, confirm the balance, or explain a difference")
  @PostMapping("/reconcile")
  public ResponseEntity<?> reconcile(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody CashWalletReconcileRequest request) {
    ResponseEntity<?> refused = guard(caller);
    return refused != null ? refused : ok(wallet.reconcile(caller, request));
  }

  private ResponseEntity<?> guard(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(ApiResponse.<Void>builder().success(false).message("Budget Insights is not available.").build());
    }
    return null;
  }

  private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).data(data).build());
  }
}
