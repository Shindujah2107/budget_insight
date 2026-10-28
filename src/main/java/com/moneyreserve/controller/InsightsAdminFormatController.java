package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRateLimit;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.domain.FormatCandidateStatus;
import com.moneyreserve.dto.AdminFormatCandidate;
import com.moneyreserve.dto.AdminFormatCandidateDraft;
import com.moneyreserve.dto.AdminFormatCandidateDraftRequest;
import com.moneyreserve.dto.AdminFormatCandidateRejectRequest;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminSenderCoverage;
import com.moneyreserve.dto.AdminSmsVocabulary;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.AdminFormatCandidateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
 * SMS format discovery for the Super Admin: the coverage report (Phase 1), the formats real
 * customers receive that no template reads (Phase 2), and a head start on a template for one
 * (Phase 3). Nothing here shows or holds a message: a format is a shape such as {@code Dear
 * Customer, bill Payment made from {W} to {W} for LKR {AMT}}, and only once 5 or more different
 * users have sent it.
 *
 * <p>Always mapped, like the rest of the admin API; with the phases off there is simply nothing to
 * list.
 */
@Tag(name = "Budget Insights admin — SMS format discovery", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminFormatController {

  static final int MAX_DAYS = 90;

  private final AdminFormatCandidateService formats;

  @Operation(summary = "Money SMS no template could read, per sender (counts only)")
  @GetMapping("/sender-coverage")
  public ResponseEntity<ApiResponse<List<AdminSenderCoverage>>> senderCoverage(
      @RequestParam(defaultValue = "30") int days) {
    if (days < 1 || days > MAX_DAYS) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_RANGE_INVALID,
          "That date range is not valid.",
          AdminFieldError.of("days", "must be 1 to " + MAX_DAYS));
    }
    return AdminHttp.ok(formats.senderCoverage(days));
  }

  @Operation(summary = "SMS formats sent by 5 or more customers that no template reads")
  @GetMapping("/format-candidates")
  public ResponseEntity<ApiResponse<AdminPage<AdminFormatCandidate>>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String senderId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    String chosen = AdminHttp.oneOf("status", status, "OPEN", "RESOLVED", "REJECTED");
    return AdminHttp.ok(
        formats.list(
            chosen == null ? FormatCandidateStatus.OPEN : FormatCandidateStatus.valueOf(chosen),
            senderId,
            AdminHttp.page(page),
            size));
  }

  @Operation(summary = "One SMS format")
  @GetMapping("/format-candidates/{id}")
  public ResponseEntity<ApiResponse<AdminFormatCandidate>> get(@PathVariable UUID id) {
    return AdminHttp.ok(formats.get(id));
  }

  @Operation(
      summary =
          "Start a template from an SMS format: a sample with invented values and the wizard's analysis")
  @AdminRateLimit(AdminRateLimit.Bucket.BUILDER)
  @PostMapping("/format-candidates/{id}/draft-template")
  public ResponseEntity<ApiResponse<AdminFormatCandidateDraft>> draftTemplate(
      @PathVariable UUID id, @Valid @RequestBody AdminFormatCandidateDraftRequest request) {
    return AdminHttp.ok(formats.draftTemplate(id, request.bankCode()));
  }

  @Operation(summary = "Mark an SMS format as not one to template (a promotion, not a transaction…)")
  @AdminWrite
  @PostMapping("/format-candidates/{id}/reject")
  public ResponseEntity<ApiResponse<AdminFormatCandidate>> reject(
      @AuthenticationPrincipal Caller caller,
      @PathVariable UUID id,
      @Valid @RequestBody AdminFormatCandidateRejectRequest request) {
    return AdminHttp.ok(formats.reject(id, request.reason(), AdminHttp.userId(caller)));
  }

  @Operation(summary = "The words a phone may name in an SMS format, by id")
  @GetMapping("/sms-vocabulary")
  public ResponseEntity<ApiResponse<AdminSmsVocabulary>> vocabulary() {
    return AdminHttp.ok(formats.vocabulary());
  }
}
