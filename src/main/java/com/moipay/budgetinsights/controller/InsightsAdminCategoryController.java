package com.moipay.budgetinsights.controller;

import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.AdminRoles;
import com.moipay.budgetinsights.config.AdminWrite;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.dto.AdminCategoryInput;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminConfigItem;
import com.moipay.budgetinsights.dto.AdminConfigList;
import com.moipay.budgetinsights.dto.AdminDeactivateCategoryRequest;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import com.moipay.budgetinsights.service.AdminCategoryService;
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
 * Budget Insights admin — Spending Categories (screens 02-04).
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches in {@code AdminAccessGuard}. Every write
 * is a draft in the shared changeset and reaches handsets and the categoriser only through {@code
 * POST /admin/config/publish}. Writes accept {@code If-Match: <changesetRevision>}.
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminCategoryController {

  private final AdminCategoryService categories;

  // --- spending categories --------------------------------------------------------------------

  @Operation(summary = "Spending categories (effective view) with usage")
  @GetMapping("/categories")
  public ResponseEntity<ApiResponse<AdminConfigList<AdminConfigItem<AdminCategoryState>>>>
      categories(
          @RequestParam(required = false) String q,
          @RequestParam(required = false) String active) {
    return AdminHttp.ok(categories.list(q, AdminHttp.activeFilter(active)));
  }

  @Operation(summary = "Draft a new category")
  @AdminWrite
  @PostMapping("/categories")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminCategoryState>>>>
      createCategory(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @Valid @RequestBody AdminCategoryInput body) {
    return AdminHttp.created(
        categories.create(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft an edit to a category")
  @AdminWrite
  @PutMapping("/categories/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminCategoryState>>>>
      updateCategory(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id,
          @Valid @RequestBody AdminCategoryInput body) {
    return AdminHttp.draft(
        categories.update(id, body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft deactivating a category; its merchant rules move or turn off")
  @AdminWrite
  @PostMapping("/categories/{id}/deactivate")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminCategoryState>>>>
      deactivateCategory(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id,
          @RequestBody(required = false) AdminDeactivateCategoryRequest body) {
    UUID moveTo = body == null ? null : body.moveMerchantRulesTo();
    return AdminHttp.draft(
        categories.deactivate(id, moveTo, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft reactivating a category")
  @AdminWrite
  @PostMapping("/categories/{id}/activate")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminCategoryState>>>>
      activateCategory(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id) {
    return AdminHttp.draft(
        categories.activate(id, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "What uses a category, and whether it can be deleted")
  @GetMapping("/categories/{id}/usage")
  public ResponseEntity<ApiResponse<AdminCategoryService.DeleteCheck>> categoryUsage(
      @PathVariable UUID id) {
    return AdminHttp.ok(categories.usage(id));
  }

  @Operation(summary = "Draft deleting an unused category")
  @AdminWrite
  @DeleteMapping("/categories/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminCategoryState>>>>
      deleteCategory(
          @AuthenticationPrincipal Caller caller,
          @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
          @PathVariable UUID id) {
    return AdminHttp.draft(
        categories.delete(id, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }
}
