package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AuthProperties;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AuthCodeSentResponse;
import com.moneyreserve.dto.AuthLoginRequest;
import com.moneyreserve.dto.AuthRefreshRequest;
import com.moneyreserve.dto.AuthRegisterRequest;
import com.moneyreserve.dto.AuthTokenResponse;
import com.moneyreserve.dto.AuthUserDto;
import com.moneyreserve.dto.AuthVerifyRequest;
import com.moneyreserve.dto.DataErasureResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App sign-in for the standalone MoneyReserve app: mobile number + SMS code, no passwords.
 *
 * <p>Register, log-in, verify, refresh and log-out are public (they are how a caller gets a token);
 * {@code /me} and {@code /account} need the access token. Every route answers 404 while {@code
 * moneyreserve.insights.auth.enabled} is off.
 */
@Tag(name = "App sign-in")
@RestController
@RequestMapping(AuthController.BASE_PATH)
@RequiredArgsConstructor
public class AuthController {

  public static final String BASE_PATH = "/api/auth";

  /** Routes that must be reachable without a token. */
  public static final String[] PUBLIC_ROUTES = {
    BASE_PATH + "/register",
    BASE_PATH + "/login",
    BASE_PATH + "/verify",
    BASE_PATH + "/refresh",
    BASE_PATH + "/logout"
  };

  private final AuthProperties properties;
  private final AuthService auth;

  @Operation(summary = "Start sign-up: texts a 6-digit code to the number")
  @PostMapping("/register")
  public ResponseEntity<ApiResponse<AuthCodeSentResponse>> register(
      @Valid @RequestBody AuthRegisterRequest request) {
    requireEnabled();
    return ok("We sent a code to your number.", auth.register(request));
  }

  @Operation(summary = "Log in: texts a 6-digit code to the number")
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<AuthCodeSentResponse>> login(
      @Valid @RequestBody AuthLoginRequest request) {
    requireEnabled();
    return ok("We sent a code to your number.", auth.login(request));
  }

  @Operation(summary = "Check the code and sign in: returns an access token and a refresh token")
  @PostMapping("/verify")
  public ResponseEntity<ApiResponse<AuthTokenResponse>> verify(
      @Valid @RequestBody AuthVerifyRequest request) {
    requireEnabled();
    return ok("Signed in.", auth.verify(request));
  }

  @Operation(summary = "Exchange a refresh token for a new token pair (the old one stops working)")
  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<AuthTokenResponse>> refresh(
      @Valid @RequestBody AuthRefreshRequest request) {
    requireEnabled();
    return ok(null, auth.refresh(request.refreshToken()));
  }

  @Operation(summary = "Log out: ends the session the refresh token belongs to")
  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody AuthRefreshRequest request) {
    requireEnabled();
    auth.logout(request.refreshToken());
    return ok("Logged out.", null);
  }

  @Operation(summary = "The signed-in customer")
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<AuthUserDto>> me(@AuthenticationPrincipal Caller caller) {
    requireEnabled();
    return ok(null, auth.me(requireCaller(caller)));
  }

  @Operation(
      summary =
          "Delete the account: erases all Budget Insights data, ends every session, removes the login")
  @DeleteMapping("/account")
  public ResponseEntity<ApiResponse<DataErasureResponse>> deleteAccount(
      @AuthenticationPrincipal Caller caller) {
    requireEnabled();
    return ok("Account and data deleted.", auth.deleteAccount(requireCaller(caller)));
  }

  private void requireEnabled() {
    if (!properties.isEnabled()) {
      throw new BudgetInsightsException(ErrorCode.AUTH_DISABLED);
    }
  }

  private static Caller requireCaller(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException("This endpoint needs a signed-in customer.");
    }
    return caller;
  }

  private static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).message(message).data(data).build());
  }
}
