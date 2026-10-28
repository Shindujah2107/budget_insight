package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AdminBankInput;
import com.moneyreserve.dto.AdminBankItem;
import com.moneyreserve.dto.AdminBankUpdate;
import com.moneyreserve.dto.AdminBudgetPolicyInput;
import com.moneyreserve.dto.AdminBudgetPolicyView;
import com.moneyreserve.dto.AdminConfigSummary;
import com.moneyreserve.dto.AdminMessageTypeInput;
import com.moneyreserve.dto.AdminMessageTypeItem;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.service.AdminBankService;
import com.moneyreserve.service.AdminBudgetPolicyService;
import com.moneyreserve.service.AdminConfigService;
import com.moneyreserve.service.AdminMessageTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
 * Budget Insights admin — Configuration: shared reads, spending categories and bank SMS senders.
 *
 * <p>Super Admin only ({@code SecurityConfig}); feature switches in {@code AdminAccessGuard}. Every
 * write is a draft in the shared changeset and reaches handsets only through {@code POST
 * /admin/config/publish}. Writes accept {@code If-Match: <changesetRevision>}.
 */
/**
 * Budget Insights admin — Configuration, shared reads (tab counters and the pending-changes bar,
 * the bank list, the app's icon keys), bank edits and the budget policy. Super Admin only ({@code SecurityConfig}); switches in
 * {@code AdminAccessGuard}.
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminConfigController {

  private final AdminConfigService config;
  private final AdminBankService bankRegistry;
  private final AdminBudgetPolicyService budgetPolicy;
  private final AdminMessageTypeService messageTypes;

  // --- shared reads -------------------------------------------------------------------------

  @Operation(summary = "Tab counters, release version and pending-change count")
  @GetMapping("/summary")
  public ResponseEntity<ApiResponse<AdminConfigSummary>> summary() {
    return AdminHttp.ok(config.summary());
  }

  // --- banks (adding is immediate; edits of a live bank are drafts) -------------------------

  @Operation(
      summary = "Banks Budget Insights supports, with usage",
      description = "Active banks by default (what the sender / template dropdowns offer); "
          + "?active=all for the management list, ?active=false for turned-off banks.")
  @GetMapping("/banks")
  public ResponseEntity<ApiResponse<List<AdminBankItem>>> banks(
      @RequestParam(required = false) String active) {
    return AdminHttp.ok(bankRegistry.list(AdminHttp.activeFilter(active)));
  }

  @Operation(
      summary = "Add a bank",
      description = "Available in every bank dropdown immediately. bankCode is the 4-digit CBSL code "
          + "and cannot be changed later.")
  @AdminWrite
  @PostMapping("/banks")
  public ResponseEntity<ApiResponse<AdminBankItem>> createBank(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody AdminBankInput body) {
    AdminBankItem item = bankRegistry.create(body, AdminHttp.userId(caller));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.<AdminBankItem>builder().success(true).message("Bank added").data(item).build());
  }

  @Operation(
      summary = "Rename a bank, or turn it on / off",
      description = "Saved as a draft for a live bank (state DRAFT_EDITED / DRAFT_DEACTIVATED, live "
          + "values in `published`): it applies with the next publish and is kept in release history. "
          + "A bank still pending with its first sender draft changes directly. Turning a bank off is "
          + "refused (409 E.023) while active senders or templates use it. The response carries "
          + "`changesetRevision` for the next If-Match. If-Match is optional here (recommended).")
  @AdminWrite
  @PutMapping("/banks/{bankCode}")
  public ResponseEntity<ApiResponse<AdminBankItem>> updateBank(
      @AuthenticationPrincipal Caller caller,
      @PathVariable String bankCode,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AdminBankUpdate body) {
    return AdminHttp.ok(
        "Bank change saved",
        bankRegistry.update(bankCode, body, AdminHttp.userId(caller), AdminHttp.optionalRevision(ifMatch)));
  }

  @Operation(
      summary = "Delete a bank that nothing ever used",
      description = "For mistakes. A draft for a live bank (state DRAFT_DELETED until published). "
          + "Refused (409 E.023) once senders, templates or customer accounts refer to it — turn it "
          + "off instead.")
  @AdminWrite
  @DeleteMapping("/banks/{bankCode}")
  public ResponseEntity<ApiResponse<AdminWriteResult<Void>>> deleteBank(
      @AuthenticationPrincipal Caller caller,
      @PathVariable String bankCode,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch) {
    long revision = bankRegistry.delete(bankCode, AdminHttp.userId(caller), AdminHttp.optionalRevision(ifMatch));
    return AdminHttp.ok("Bank deletion saved", new AdminWriteResult<>(null, revision));
  }

  // --- message types (Configuration › Message types; changes apply immediately) -------------

  @Operation(
      summary = "Message types SMS templates pick from, with usage",
      description = "Active types by default (what the template wizard offers); ?active=all for the "
          + "management list, ?active=false for turned-off types. `logicalType` is the group new "
          + "templates of the type take; `defaultDirection` / `defaultInstrument` are the wizard's "
          + "defaults; `fixedCategoryCode` categorises every SMS of the type without merchant rules.")
  @GetMapping("/message-types")
  public ResponseEntity<ApiResponse<List<AdminMessageTypeItem>>> messageTypes(
      @RequestParam(required = false) String active) {
    return AdminHttp.ok(messageTypes.list(AdminHttp.activeFilter(active)));
  }

  @Operation(
      summary = "Add a message type",
      description = "Offered in the template wizard immediately. `code` is UPPER_SNAKE (2-32) and "
          + "cannot be changed later; 409 E.008 when it exists. `logicalType` is one of "
          + "ACCOUNT_TRANSACTION, CARD_PURCHASE, ATM_WITHDRAWAL, CASH_DEPOSIT, TRANSFER, PAYMENT, "
          + "CHEQUE, LOAN, BANK_CHARGE_OR_REFUND, REFUND_OR_REVERSAL.")
  @AdminWrite
  @PostMapping("/message-types")
  public ResponseEntity<ApiResponse<AdminMessageTypeItem>> createMessageType(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody AdminMessageTypeInput body) {
    AdminMessageTypeItem item = messageTypes.create(body, AdminHttp.userId(caller));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.<AdminMessageTypeItem>builder()
            .success(true).message("Message type added").data(item).build());
  }

  @Operation(
      summary = "Change a message type, or turn it on / off",
      description = "Replaces display name, group, defaults and fixed category (blank = merchant "
          + "rules); `code` in the body is ignored. Applies immediately. Existing templates keep "
          + "their group; turning a type off only stops new templates from picking it.")
  @AdminWrite
  @PutMapping("/message-types/{code}")
  public ResponseEntity<ApiResponse<AdminMessageTypeItem>> updateMessageType(
      @AuthenticationPrincipal Caller caller,
      @PathVariable String code,
      @Valid @RequestBody AdminMessageTypeInput body) {
    return AdminHttp.ok("Message type saved", messageTypes.update(code, body, AdminHttp.userId(caller)));
  }

  // --- budget policy (Configuration › Budget Policy) ---------------------------------------

  @Operation(
      summary = "The budget policy: live values, values after publish, last published",
      description = "50/30/20 split, recommendation period, default category alert threshold, pace "
          + "tolerance and which budget alert types are raised. Applies to every customer.")
  @GetMapping("/budget-policy")
  public ResponseEntity<ApiResponse<AdminBudgetPolicyView>> getBudgetPolicy() {
    return AdminHttp.ok(budgetPolicy.get());
  }

  @Operation(
      summary = "Save the budget policy as a draft",
      description = "The whole policy, every field required. needsPct + wantsPct + savingsPct = 100; "
          + "trailingMonths 1-12; defaultAlertPct 50-95; paceTolerancePct 0-25 (400 E.006 with a "
          + "field error otherwise). Goes live with the next publish; turning every alert type off "
          + "is a publish warning.")
  @AdminWrite
  @PutMapping("/budget-policy")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminBudgetPolicyView>>> updateBudgetPolicy(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AdminBudgetPolicyInput body) {
    return AdminHttp.ok(
        "Budget policy saved as a draft",
        budgetPolicy.update(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Discard the pending budget policy change")
  @AdminWrite
  @DeleteMapping("/budget-policy/draft")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminBudgetPolicyView>>> discardBudgetPolicy(
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch) {
    return AdminHttp.ok("Budget policy draft discarded", budgetPolicy.discard(AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Icon keys the mobile app ships")
  @GetMapping("/icon-keys")
  public ResponseEntity<ApiResponse<List<String>>> iconKeys() {
    return AdminHttp.ok(config.iconKeys());
  }
}
