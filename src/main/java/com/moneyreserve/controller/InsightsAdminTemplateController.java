package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRateLimit;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminTemplateAnalyseRequest;
import com.moneyreserve.dto.AdminTemplateAnalysis;
import com.moneyreserve.dto.AdminTemplateCheck;
import com.moneyreserve.dto.AdminTemplateGenerateRequest;
import com.moneyreserve.dto.AdminTemplateGenerated;
import com.moneyreserve.dto.AdminTemplateInput;
import com.moneyreserve.dto.AdminTemplateItem;
import com.moneyreserve.dto.AdminTemplateValidateRequest;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.service.AdminTemplateService;
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
 * Budget Insights admin — Configuration, SMS Parser Templates (T1–T8, screens 14–22).
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches and rate limits in {@code
 * AdminAccessGuard}. The builder routes take a redacted sample, never store or log it, and return
 * offsets and extracted values only. Writes are drafts and need {@code If-Match}.
 */
@Tag(name = "Budget Insights admin — SMS parser templates", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminTemplateController {

  private final AdminTemplateService templates;

  @Operation(summary = "Templates per bank and message type (no patterns)")
  @GetMapping("/templates")
  public ResponseEntity<ApiResponse<AdminConfigList<AdminTemplateItem>>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String active,
      @RequestParam(required = false) String bank) {
    return AdminHttp.ok(templates.list(q, AdminHttp.activeFilter(active), bank));
  }

  @Operation(summary = "One template with pattern, field map, builder spec and version history")
  @GetMapping("/templates/{id}")
  public ResponseEntity<ApiResponse<AdminTemplateItem>> get(@PathVariable UUID id) {
    return AdminHttp.ok(templates.get(id));
  }

  @Operation(summary = "Wizard: analyse a redacted sample (suggestions and privacy warnings)")
  @AdminRateLimit(AdminRateLimit.Bucket.BUILDER)
  @PostMapping("/templates/builder/analyse")
  public ResponseEntity<ApiResponse<AdminTemplateAnalysis>> analyse(
      @Valid @RequestBody AdminTemplateAnalyseRequest body) {
    return AdminHttp.ok(templates.analyse(body));
  }

  @Operation(summary = "Wizard: generate pattern and field map from highlights, with checks")
  @AdminRateLimit(AdminRateLimit.Bucket.BUILDER)
  @PostMapping("/templates/builder/generate")
  public ResponseEntity<ApiResponse<AdminTemplateGenerated>> generate(
      @Valid @RequestBody AdminTemplateGenerateRequest body) {
    return AdminHttp.ok(templates.generate(body));
  }

  @Operation(summary = "Advanced mode: check a raw pattern and field map")
  @AdminRateLimit(AdminRateLimit.Bucket.BUILDER)
  @PostMapping("/templates/validate")
  public ResponseEntity<ApiResponse<AdminTemplateCheck>> validate(
      @Valid @RequestBody AdminTemplateValidateRequest body) {
    return AdminHttp.ok(templates.validate(body));
  }

  @Operation(summary = "Draft a new template (BUILDER spec or ADVANCED pattern)")
  @AdminWrite
  @PostMapping("/templates")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminTemplateItem>>> create(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @Valid @RequestBody AdminTemplateInput body) {
    return AdminHttp.created(templates.create(body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft an edit: publishing creates the next version")
  @AdminWrite
  @PutMapping("/templates/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminTemplateItem>>> update(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id,
      @Valid @RequestBody AdminTemplateInput body) {
    return AdminHttp.draft(templates.update(id, body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Draft retiring a template (never deleted)")
  @AdminWrite
  @DeleteMapping("/templates/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminTemplateItem>>> retire(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id) {
    return AdminHttp.draft(templates.retire(id, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }
}
