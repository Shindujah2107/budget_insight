package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AdminBillerInput;
import com.moneyreserve.dto.AdminBillerState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminRecurringPayee;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.service.AdminBillerService;
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
 * Budget Insights admin — Spending → Billers (bills radar). Super Admin only ({@code
 * SecurityConfig}). Every write is a draft in the shared changeset and reaches the app only through
 * {@code POST /admin/config/publish}. Writes accept {@code If-Match: <changesetRevision>}.
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminBillerController {

  private final AdminBillerService billers;

  @Operation(summary = "Billers (effective view) with how many customers follow bills from each")
  @GetMapping("/billers")
  public ResponseEntity<ApiResponse<AdminConfigList<AdminConfigItem<AdminBillerState>>>> list(
      @RequestParam(required = false) String q, @RequestParam(required = false) String active) {
    return AdminHttp.ok(billers.list(q, AdminHttp.activeFilter(active)));
  }

  @Operation(summary = "Draft a new biller")
  @AdminWrite
  @PostMapping("/billers")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminBillerState>>>> create(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AdminBillerInput body) {
    return AdminHttp.created(billers.create(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft an edit to a biller")
  @AdminWrite
  @PutMapping("/billers/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminBillerState>>>> update(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id,
      @Valid @RequestBody AdminBillerInput body) {
    return AdminHttp.draft(billers.update(id, body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft turning a biller off")
  @AdminWrite
  @PostMapping("/billers/{id}/deactivate")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminBillerState>>>> deactivate(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id) {
    return AdminHttp.draft(billers.setActive(id, false, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft turning a biller back on")
  @AdminWrite
  @PostMapping("/billers/{id}/activate")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminBillerState>>>> activate(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id) {
    return AdminHttp.draft(billers.setActive(id, true, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft deleting a biller no customer's bill comes from")
  @AdminWrite
  @DeleteMapping("/billers/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminBillerState>>>> delete(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id) {
    return AdminHttp.draft(billers.delete(id, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Payees several customers pay regularly that match no biller (counts only)")
  @GetMapping("/recurring-payees")
  public ResponseEntity<ApiResponse<List<AdminRecurringPayee>>> recurringPayees() {
    return AdminHttp.ok(billers.recurringPayees());
  }
}
