package com.moipay.budgetinsights.controller;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.dto.BudgetSummaryResponse;
import com.moipay.budgetinsights.dto.UpsertBudgetRequest;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.CallerUnidentifiedException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.service.BudgetService;
import com.moipay.budgetinsights.service.InsightsQueryService;
import com.moipay.budgetinsights.service.InsightsStubFixtures;
import com.moipay.budgetinsights.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read endpoints behind the Insights screen.
 *
 * <p>The whole screen renders in four calls: summary, activity, breakdown and accounts. A fifth,
 * the per-category transaction list, is loaded only when the customer expands a row.
 *
 * <p><b>Three modes, per endpoint.</b> With the feature off, everything is 404 — the Insights tab is
 * meant to look absent, not broken. With {@code moipay.budget-insights.stub.enabled} on, every
 * endpoint answers from a fixture set, which is what let the frontend integrate against the
 * published contract in R0 before any of this existed. With the feature on and the stub off, each
 * endpoint serves whatever is actually built: as of R5 that is the monthly budget, the summary,
 * the breakdown, the per-category list, the account filter, the category reference list and the
 * payment activity chart.
 *
 * <p><b>Release flags.</b> {@code moipay.budget-insights.enabled} is the master switch; each
 * release has its own flag beneath it, so a release can be turned off without taking the whole
 * screen away. R2's is {@code moipay.budget-insights.budget.enabled}, and with it off the summary
 * answers 501 exactly as it did before R2 was written.
 *
 * <p><b>Authorisation.</b> There is no {@code @PreAuthorize} here, matching CaseEngine, FeeEngine
 * and SplitEngine. Core guarded these endpoints with {@code SCOPE_INSIGHTS_READ}, resolved from its
 * own {@code rbac} schema; this service has no such schema. The control is that every query is
 * scoped to {@link Caller#userId()} and no endpoint accepts a user identifier in a path, query or
 * body — so there is no request shape that could address another customer's ledger.
 */
@Slf4j
@Tag(name = "Budget Insights", description = "Monthly budget, payment activity and spending breakdown")
@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
@Validated
public class InsightsController {

  private final InsightsProperties properties;
  private final InsightsStubFixtures fixtures;
  private final InsightsQueryService queries;
  private final RecommendationService recommendations;
  private final BudgetService budgets;

  @Operation(summary = "Monthly budget summary: ring, target, utilisation and days text")
  @GetMapping("/summary")
  public ResponseEntity<?> summary(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "false") boolean simulateNoBudget) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (!stubbing()) {
      if (!budgetReleased()) {
        return notImplemented();
      }
      return ok(budgets.summary(caller, parseMonth(period)));
    }
    YearMonth month = parseMonth(period);
    BudgetSummaryResponse body =
        simulateNoBudget ? fixtures.summaryWithoutBudget(month) : fixtures.summary(month);
    return ok(body);
  }

  @Operation(summary = "The monthly target and any category limits set for a period")
  @GetMapping("/budget")
  public ResponseEntity<?> getBudget(
      @AuthenticationPrincipal Caller caller, @RequestParam(required = false) String period) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (!budgetReleased()) {
      return notImplemented();
    }
    return ok(budgets.get(caller, parseMonth(period)));
  }

  @Operation(summary = "Set or replace the monthly target and category limits for a period")
  @PutMapping("/budget")
  public ResponseEntity<?> putBudget(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody UpsertBudgetRequest request) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (!budgetReleased()) {
      return notImplemented();
    }
    // bi_budget.tenant_id is NOT NULL and the gateway filter permits a caller with no tenant, so
    // without this the INSERT is where the request failed -- a 500 on valid credentials. This is
    // the only write on this controller; every other route here reads.
    requireTenant(caller);
    return ok(budgets.upsert(caller, request));
  }

  @Operation(summary = "Daily spend for one week, split into card and bank account series")
  @GetMapping("/activity")
  public ResponseEntity<?> activity(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate weekStart,
      @RequestParam(required = false) UUID accountId) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (stubbing()) {
      return ok(fixtures.activity(weekStart, accountId));
    }
    if (!activityReleased()) {
      return notImplemented();
    }
    return ok(queries.activity(caller.userId(), weekStart, accountId));
  }

  /**
   * 50/30/20 guidance for the month being viewed (BI-26).
   *
   * <p>No stub branch, unlike its neighbours. The fixture set was built in R0 for endpoints the
   * frontend needed before the logic existed; this one had no fixture and gains nothing from an
   * invented one, since every number it returns is a statement about a particular customer's own
   * history.
   */
  @Operation(summary = "50/30/20 guidance from the customer's target and trailing spend")
  @GetMapping("/recommendations")
  public ResponseEntity<?> recommendations(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (!properties.getRecommendations().isEnabled()) {
      return notImplemented();
    }
    return ok(recommendations.recommend(caller.userId(), on));
  }

  @Operation(summary = "Spending breakdown for a period: total and ranked categories")
  @GetMapping("/breakdown")
  public ResponseEntity<?> breakdown(
      @AuthenticationPrincipal Caller caller,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (stubbing()) {
      return ok(fixtures.breakdown(from, to));
    }
    return ok(queries.breakdown(caller.userId(), from, to));
  }

  @Operation(summary = "Transactions behind one category, loaded when the row is expanded")
  @GetMapping("/breakdown/{categoryId}/transactions")
  public ResponseEntity<?> categoryTransactions(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID categoryId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {

    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (stubbing()) {
      return ok(fixtures.categoryTransactions(categoryId, page, size));
    }
    return ok(queries.categoryTransactions(caller.userId(), categoryId, from, to, page, size));
  }

  @Operation(summary = "Linked accounts for the All Accounts filter, masked tails only")
  @GetMapping("/accounts")
  public ResponseEntity<?> accounts(@AuthenticationPrincipal Caller caller) {
    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (stubbing()) {
      return okList(fixtures.accounts());
    }
    return okList(queries.accounts(caller.userId()));
  }

  @Operation(summary = "The supported spending categories, in display order")
  @GetMapping("/categories")
  public ResponseEntity<?> categories(@AuthenticationPrincipal Caller caller) {
    requireCaller(caller);
    ResponseEntity<?> unavailable = featureGuard();
    if (unavailable != null) {
      return unavailable;
    }
    if (stubbing()) {
      return okList(fixtures.categories());
    }
    return okList(queries.categories());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * A Caller is only ever built by the gateway filter. It is null when the service is reached in
   * local direct mode (HTTP basic), where there is an authenticated admin but no customer — and no
   * customer means there is no ledger to read.
   */
  private void requireCaller(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new CallerUnidentifiedException(
          "This endpoint needs a customer identity from the gateway.");
    }
  }

  /**
   * For the one operation on this controller that creates or updates a row.
   *
   * <p>Matches the consent, ingest and transaction controllers, which all refuse a tenantless
   * caller before writing rather than discovering it at the constraint.
   */
  private void requireTenant(Caller caller) {
    if (caller.tenantId() == null) {
      throw new CallerUnidentifiedException(
          "Saving a budget needs a tenant identity from the gateway.");
    }
  }

  private boolean stubbing() {
    return properties.getStub().isEnabled();
  }

  /**
   * The R5 release gate.
   *
   * <p>Behind stub mode rather than ahead of it, like the budget endpoints: the fixture is what the
   * frontend built the chart against in R0, and it stays authoritative in stub mode so a client
   * integrating there is unaffected by this release landing.
   *
   * <p>Note for anyone reading the chart against real data: the two series come from
   * {@code bi_account.instrument_type}, so spend with no account appears in neither. While
   * {@code masked-card-to-backend-enabled} is off that is every ingested card transaction, and the
   * card series will be empty however much real data arrives. {@code unattributedTotal} on the
   * response carries what the series cannot show.
   */
  private boolean activityReleased() {
    return properties.getActivity().isEnabled();
  }

  /**
   * The R2 release gate, separate from the master switch.
   *
   * <p>The two budget endpoints are new in R2 and have no fixture behind them, so unlike the rest
   * of this controller they are gated on the release flag alone rather than on stub mode. Stub mode
   * existed to let the frontend build against the contract before the logic did; for these two the
   * logic is what there is.
   */
  private boolean budgetReleased() {
    return properties.getBudget().isEnabled();
  }

  /** Returns a 404 body when the feature is switched off, or null to continue. */
  private ResponseEntity<?> featureGuard() {
    if (!properties.isEnabled()) {
      log.info("Insights request refused: Budget Insights is switched off (404)");
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(
              ApiResponse.<Void>builder()
                  .success(false)
                  .message("Budget Insights is not available.")
                  .build());
    }
    return null;
  }

  private ResponseEntity<?> notImplemented() {
    log.info("Insights request refused: this feature is not enabled here (501)");
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(
            ApiResponse.<Void>builder()
                .success(false)
                .message("This part of Budget Insights is not yet serving live data.")
                .build());
  }

  /**
   * Parses the requested month, or null for "whichever month it is now".
   *
   * <p>Null rather than {@code YearMonth.now()}: this container's clock is UTC and the customer's
   * month is not. Resolving an omitted period here would put the wrong month on the screen for the
   * five and a half hours after Colombo midnight on the first and last day of every month. The
   * service resolves it in the configured zone instead.
   */
  private YearMonth parseMonth(String period) {
    if (period == null || period.isBlank()) {
      return null;
    }
    try {
      return YearMonth.parse(period);
    } catch (DateTimeParseException ex) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD);
    }
  }

  private <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).data(data).build());
  }

  private <T> ResponseEntity<ApiResponse<T>> okList(List<T> items) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).items(items).build());
  }
}
