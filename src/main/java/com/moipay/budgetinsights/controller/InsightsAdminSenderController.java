package com.moipay.budgetinsights.controller;

import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.AdminRoles;
import com.moipay.budgetinsights.config.AdminWrite;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.dto.AdminConfigItem;
import com.moipay.budgetinsights.dto.AdminConfigList;
import com.moipay.budgetinsights.dto.AdminSenderImpactRequest;
import com.moipay.budgetinsights.dto.AdminSenderInput;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import com.moipay.budgetinsights.service.AdminSenderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * Budget Insights admin — Configuration: shared reads, spending categories and bank SMS senders.
 *
 * <p>Super Admin only ({@code SecurityConfig}); feature switches in {@code AdminAccessGuard}. Every
 * write is a draft in the shared changeset and reaches handsets only through {@code POST
 * /admin/config/publish}. Writes accept {@code If-Match: <changesetRevision>}.
 */
/**
 * Budget Insights admin — Bank SMS Senders (screens 11-13): the allowlist a handset checks before
 * it reads a message at all.
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches in {@code AdminAccessGuard}. Writes are
 * drafts; turning off or deleting a sender that active templates list needs {@code
 * acknowledgeTemplateImpact}.
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminSenderController {

  private final AdminSenderService senders;

  // --- bank SMS senders -----------------------------------------------------------------------

  @Operation(
      summary = "Bank SMS senders (effective view) with usage",
      description = "Join bankCode with GET /config/banks?active=all for the bank name and its "
          + "'new' (pending) flag.")
  @GetMapping("/senders")
  public ResponseEntity<ApiResponse<AdminConfigList<AdminConfigItem<AdminSenderState>>>> senders(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String active,
      @RequestParam(required = false) String bank) {
    return AdminHttp.ok(senders.list(q, AdminHttp.activeFilter(active), bank));
  }

  @Operation(
      summary = "Draft a new sender",
      description = "Send bankCode for an existing bank, or newBank {bankCode (4-digit CBSL), "
          + "bankName} to add the bank with the sender. A new bank is 'pending' until publish and "
          + "removed if the draft is discarded.")
  @AdminWrite
  @PostMapping("/senders")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminSenderState>>>>
      createSender(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @Valid @RequestBody AdminSenderInput body) {
    return AdminHttp.created(
        senders.create(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft an edit to a sender")
  @AdminWrite
  @PutMapping("/senders/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminSenderState>>>>
      updateSender(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id,
          @RequestParam(defaultValue = "false") boolean acknowledgeTemplateImpact,
          @Valid @RequestBody AdminSenderInput body) {
    return AdminHttp.draft(
        senders.update(
            id,
            body,
            acknowledgeTemplateImpact,
            AdminHttp.userId(caller),
            AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft turning a sender off")
  @AdminWrite
  @PostMapping("/senders/{id}/deactivate")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminSenderState>>>>
      deactivateSender(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id,
          @RequestBody(required = false) AdminSenderImpactRequest body) {
    boolean ack = body != null && Boolean.TRUE.equals(body.acknowledgeTemplateImpact());
    return AdminHttp.draft(
        senders.deactivate(id, ack, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft deleting a sender")
  @AdminWrite
  @DeleteMapping("/senders/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminSenderState>>>>
      deleteSender(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id,
          @RequestParam(defaultValue = "false") boolean acknowledgeTemplateImpact) {
    return AdminHttp.draft(
        senders.delete(
            id, acknowledgeTemplateImpact, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }
}
