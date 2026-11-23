package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.CallerUnidentifiedException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.impl.AskMrServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask MR (Premium Insights, V1.29.0): fixed questions about the customer's recorded spending,
 * answered as structured facts. Refused when the admin switch is off (MR.ASK.E.001); customers
 * without Premium get the teaser (headline only) while the free teaser is on, otherwise
 * MR.ASK.E.002. Read-only apart from the "Worth knowing" log and usage counts.
 */
@RestController
@RequestMapping("/api/insights/ask")
@Tag(name = "Ask Mate", description = "Premium: what changed, what contributed, what could help")
@RequiredArgsConstructor
public class AskMrController {

  private final InsightsProperties properties;
  private final AskMrServiceImpl askMr;

  @Operation(summary = "The questions Ask Mate can answer now")
  @GetMapping("/questions")
  public ResponseEntity<?> questions(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(askMr.questions(caller.userId(), accountIds));
  }

  @Operation(summary = "Compare a category: this month's categories by share, with the change vs the same dates last month")
  @GetMapping("/categories")
  public ResponseEntity<?> categories(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(askMr.categories(caller.userId(), accountIds));
  }

  @Operation(summary = "One answer: what happened, what contributed, what could help, the evidence")
  @GetMapping("/answer")
  public ResponseEntity<?> answer(
      @AuthenticationPrincipal Caller caller,
      @RequestParam String question,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String week,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(askMr.answer(caller.userId(), question, categoryId, date(week), accountIds));
  }

  @Operation(summary = "The weekly summary: \"How was my week?\" (Sunday notification)")
  @GetMapping("/weekly")
  public ResponseEntity<?> weekly(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) String week,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(askMr.weekly(caller.userId(), date(week), accountIds));
  }

  @Operation(summary = "A \"Worth knowing\" card, or none")
  @GetMapping("/worth-knowing")
  public ResponseEntity<?> worthKnowing(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(name = "accountId", required = false) List<UUID> accountIds) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    return off != null ? off : ok(askMr.worthKnowing(caller.userId(), accountIds));
  }

  @Operation(summary = "Dismiss a \"Worth knowing\" card")
  @PostMapping("/worth-knowing/{id}/dismiss")
  public ResponseEntity<?> dismiss(@AuthenticationPrincipal Caller caller, @PathVariable UUID id) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    if (off != null) {
      return off;
    }
    askMr.dismissWorthKnowing(caller.userId(), id);
    return ok(null);
  }

  @Operation(summary = "Count an opened piece of evidence (per question; nothing personal is stored)")
  @PostMapping("/evidence-opened")
  public ResponseEntity<?> evidenceOpened(
      @AuthenticationPrincipal Caller caller, @RequestBody(required = false) Map<String, String> body) {
    requireCaller(caller);
    ResponseEntity<?> off = insightsOff();
    if (off != null) {
      return off;
    }
    askMr.evidenceOpened(caller.userId(), body == null ? null : body.get("question"));
    return ok(null);
  }

  private static LocalDate date(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_BAD_REQUEST);
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
