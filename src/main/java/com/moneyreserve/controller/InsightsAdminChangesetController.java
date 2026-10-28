package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AdminChangeset;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminPublishRequest;
import com.moneyreserve.dto.AdminPublishResult;
import com.moneyreserve.dto.AdminRelease;
import com.moneyreserve.dto.AdminRestoreResult;
import com.moneyreserve.dto.AdminValidation;
import com.moneyreserve.service.AdminChangesetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget Insights admin — Review &amp; publish (screen 23) and Change history (screen 24).
 *
 * <p>Super Admin only ({@code SecurityConfig}); switches in {@code AdminAccessGuard}. Publishing is
 * the only way a configuration change reaches handsets and the categoriser: it applies the whole
 * changeset in one transaction as reference release N+1.
 */
@Tag(name = "Budget Insights admin — publish and history", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH + "/config")
@RequiredArgsConstructor
public class InsightsAdminChangesetController {

  private final AdminChangesetService changesets;

  @Operation(summary = "Every pending change, in plain words, with the revision to publish")
  @GetMapping("/changeset")
  public ResponseEntity<ApiResponse<AdminChangeset>> changeset() {
    return AdminHttp.ok(changesets.changeset());
  }

  @Operation(summary = "Discard one pending change")
  @AdminWrite
  @DeleteMapping("/changeset/changes/{changeId}")
  public ResponseEntity<ApiResponse<Map<String, Long>>> discard(
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable UUID changeId) {
    return AdminHttp.ok(
        "Change discarded",
        Map.of("revision", changesets.discard(changeId, AdminHttp.revision(ifMatch))));
  }

  @Operation(summary = "Discard every pending change")
  @AdminWrite
  @DeleteMapping("/changeset")
  public ResponseEntity<ApiResponse<Map<String, Long>>> discardAll(
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch) {
    return AdminHttp.ok(
        "All changes discarded",
        Map.of("revision", changesets.discardAll(AdminHttp.revision(ifMatch))));
  }

  @Operation(summary = "Run the pre-publish checks: errors block, warnings need acknowledging")
  @PostMapping("/changeset/validate")
  public ResponseEntity<ApiResponse<AdminValidation>> validate() {
    return AdminHttp.ok(changesets.validate());
  }

  @Operation(summary = "Publish the changeset as reference release N+1")
  @AdminWrite
  @PostMapping("/publish")
  public ResponseEntity<ApiResponse<AdminPublishResult>> publish(
      @AuthenticationPrincipal Caller caller, @Valid @RequestBody AdminPublishRequest body) {
    AdminPublishResult result = changesets.publish(body, AdminHttp.userId(caller));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.<AdminPublishResult>builder()
                .success(true)
                .message("Published v" + result.version() + ". Handsets update on their next sync.")
                .data(result)
                .build());
  }

  @Operation(summary = "Published releases, newest first")
  @GetMapping("/releases")
  public ResponseEntity<ApiResponse<AdminPage<AdminRelease>>> releases(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return AdminHttp.ok(changesets.releases(AdminHttp.page(page), size));
  }

  @Operation(summary = "One release with the before and after of every change")
  @GetMapping("/releases/{version}")
  public ResponseEntity<ApiResponse<AdminRelease>> release(@PathVariable int version) {
    return AdminHttp.ok(changesets.release(version));
  }

  @Operation(summary = "Draft the changes that bring the configuration back to an earlier release")
  @AdminWrite
  @PostMapping("/releases/{version}/restore")
  public ResponseEntity<ApiResponse<AdminRestoreResult>> restore(
      @AuthenticationPrincipal Caller caller,
      @RequestHeader(value = AdminHttp.IF_MATCH, required = false) String ifMatch,
      @PathVariable int version) {
    return AdminHttp.ok(
        "Saved as draft. Review and publish to go live.",
        changesets.restore(version, AdminHttp.userId(caller), AdminHttp.revision(ifMatch)));
  }
}
