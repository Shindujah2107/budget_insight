package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRateLimit;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AdminMerchantRuleInput;
import com.moneyreserve.dto.AdminMerchantRuleItem;
import com.moneyreserve.dto.AdminMerchantRuleTest;
import com.moneyreserve.dto.AdminMerchantRuleTestRequest;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminUncategorisedMerchant;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.service.AdminMerchantRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget Insights admin — Configuration, Merchant Rules (M1–M6, screens 05–10).
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches and rate limits in {@code
 * AdminAccessGuard}. The Admin types names and picks EXACT / PREFIX / REGEX; the server generates
 * the stored pattern. Writes are drafts and need {@code If-Match: <changesetRevision>}.
 */
@Tag(name = "Budget Insights admin — merchant rules", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminMerchantRuleController {

  private final AdminMerchantRuleService rules;

  @Operation(summary = "Merchant rules in evaluation order (names, never patterns)")
  @GetMapping("/merchant-rules")
  public ResponseEntity<ApiResponse<AdminPage<AdminMerchantRuleItem>>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String matchType,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String active,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return AdminHttp.ok(
        rules.list(
            q,
            AdminHttp.oneOf("matchType", matchType, "EXACT", "PREFIX", "REGEX", "TERMS"),
            categoryId,
            AdminHttp.activeFilter(active),
            AdminHttp.page(page),
            size));
  }

  @Operation(summary = "Merchants left in Other over the last 30 days, most frequent first (counts only)")
  @GetMapping("/merchant-rules/uncategorised")
  public ResponseEntity<ApiResponse<List<AdminUncategorisedMerchant>>> uncategorised(
      @RequestParam(defaultValue = "50") int limit) {
    return AdminHttp.ok(rules.uncategorised(limit));
  }

  @Operation(summary = "One merchant rule with its stored pattern and priority (Advanced settings)")
  @GetMapping("/merchant-rules/{id}")
  public ResponseEntity<ApiResponse<AdminMerchantRuleItem>> get(@PathVariable UUID id) {
    return AdminHttp.ok(rules.get(id));
  }

  @Operation(summary = "Test a rule against a transaction description (nothing is saved)")
  @AdminRateLimit(AdminRateLimit.Bucket.RULE_TEST)
  @PostMapping("/merchant-rules/test")
  public ResponseEntity<ApiResponse<AdminMerchantRuleTest>> test(
      @Valid @RequestBody AdminMerchantRuleTestRequest body) {
    return AdminHttp.ok(rules.test(body));
  }

  @Operation(summary = "Draft a new merchant rule")
  @AdminWrite
  @PostMapping("/merchant-rules")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminMerchantRuleItem>>> create(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AdminMerchantRuleInput body) {
    return AdminHttp.created(rules.create(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft an edit to a merchant rule (including turning it off)")
  @AdminWrite
  @PutMapping("/merchant-rules/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminMerchantRuleItem>>> update(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id,
      @Valid @RequestBody AdminMerchantRuleInput body) {
    return AdminHttp.draft(rules.update(id, body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft deleting a merchant rule")
  @AdminWrite
  @DeleteMapping("/merchant-rules/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminMerchantRuleItem>>> delete(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id) {
    return AdminHttp.draft(rules.delete(id, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }
}
