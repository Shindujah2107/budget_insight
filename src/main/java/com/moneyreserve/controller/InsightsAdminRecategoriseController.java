package com.moneyreserve.controller;

import com.moneyreserve.common.dto.ApiResponse;
import com.moneyreserve.config.AdminRoles;
import com.moneyreserve.config.AdminWrite;
import com.moneyreserve.config.Caller;
import com.moneyreserve.service.impl.AdminRecategoriseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget Insights admin — "Re-sort old transactions" (V1.31.0). Super Admin only ({@code
 * SecurityConfig}). Preview what the current merchant rules would move, apply it (each move
 * logged), and undo a run. Only payments the app categorised by itself are ever moved.
 */
@Tag(name = "Budget Insights admin — configuration", description = "Super Admin only")
@RestController
@RequestMapping(AdminRoles.BASE_PATH)
@RequiredArgsConstructor
public class InsightsAdminRecategoriseController {

  private final AdminRecategoriseService resort;

  @Operation(summary = "Preview: how many auto-categorised payments the current rules would move, from → to")
  @GetMapping("/recategorise/preview")
  public ResponseEntity<ApiResponse<AdminRecategoriseService.Preview>> preview() {
    return AdminHttp.ok(resort.preview());
  }

  @Operation(summary = "Apply the re-sort now (logged per payment, can be undone)")
  @AdminWrite
  @PostMapping("/recategorise/apply")
  public ResponseEntity<ApiResponse<AdminRecategoriseService.Run>> apply(@AuthenticationPrincipal Caller caller) {
    return AdminHttp.ok(resort.apply(AdminHttp.userId(caller)));
  }

  @Operation(summary = "Recent re-sort runs")
  @GetMapping("/recategorise/runs")
  public ResponseEntity<ApiResponse<List<AdminRecategoriseService.Run>>> runs() {
    return AdminHttp.ok(resort.runs());
  }

  @Operation(summary = "Undo a re-sort run: payments still where it left them go back")
  @AdminWrite
  @PostMapping("/recategorise/runs/{id}/undo")
  public ResponseEntity<ApiResponse<AdminRecategoriseService.UndoResult>> undo(
      @AuthenticationPrincipal Caller caller, @PathVariable UUID id) {
    return AdminHttp.ok(resort.undo(id, AdminHttp.userId(caller)));
  }
}
