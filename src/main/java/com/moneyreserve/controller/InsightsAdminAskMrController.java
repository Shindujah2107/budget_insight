package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAskMrUsage;
import com.moneyreserve.dto.AdminAskMrTipInput;
import com.moneyreserve.dto.AdminAskMrTipState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.service.impl.AdminAskMrServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget Insights admin — Spending → Ask MR (V1.29.0). Super Admin only ({@code SecurityConfig}).
 * Suggestion wording is drafted into the shared changeset and reaches the app only through {@code
 * POST /admin/config/publish}; usage is counts per question per day. The switches and thresholds
 * are part of the budget policy ({@code PUT /admin/config/budget-policy}, field {@code askMr}).
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH)
@RequiredArgsConstructor
public class InsightsAdminAskMrController {

  private final AdminAskMrServiceImpl askMr;
  private final InsightsProperties properties;

  @Operation(summary = "Ask Mate suggestion wording (effective view)")
  @GetMapping("/config/ask-mr-tips")
  public ResponseEntity<ApiResponse<AdminConfigList<AdminConfigItem<AdminAskMrTipState>>>> tips() {
    return AdminHttp.ok(askMr.tips());
  }

  @Operation(summary = "Draft new wording for an Ask Mate suggestion (same placeholders, no blaming words)")
  @AdminWrite
  @PutMapping("/config/ask-mr-tips/{id}")
  public ResponseEntity<ApiResponse<AdminWriteResult<AdminConfigItem<AdminAskMrTipState>>>> update(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID id,
      @Valid @RequestBody AdminAskMrTipInput body) {
    return AdminHttp.draft(askMr.update(id, body, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }

  @Operation(summary = "Ask Mate usage: answers shown, evidence opened, cards dismissed, per question per day")
  @GetMapping("/ask-mr/usage")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> usage(@RequestParam(defaultValue = "30") int days) {
    List<Map<String, Object>> rows =
        askMr.usage(days, LocalDate.now(properties.zoneId())).stream()
            .map(
                (BiAskMrUsage u) ->
                    Map.<String, Object>of(
                        "day", u.getDay().toString(),
                        "question", u.getQuestion(),
                        "shown", u.getShown(),
                        "evidence", u.getEvidence(),
                        "dismissed", u.getDismissed()))
            .toList();
    return AdminHttp.ok(rows);
  }
}
