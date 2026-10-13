package com.moipay.budgetinsights.controller;

import com.moipay.budgetinsights.common.dto.ApiResponse;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Response and header helpers shared by the Budget Insights admin controllers. */
final class AdminHttp {

  static final String IF_MATCH = "If-Match";

  private AdminHttp() {}

  static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
    return ResponseEntity.ok(ApiResponse.<T>builder().success(true).data(data).build());
  }

  static <T> ResponseEntity<ApiResponse<T>> ok(String message, T data) {
    return ResponseEntity.ok(
        ApiResponse.<T>builder().success(true).message(message).data(data).build());
  }

  /** 200 for a draft write. */
  static <T> ResponseEntity<ApiResponse<T>> draft(T data) {
    return ok("Saved as draft", data);
  }

  /** 201 for a new draft item. */
  static <T> ResponseEntity<ApiResponse<T>> created(T data) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.<T>builder().success(true).message("Saved as draft").data(data).build());
  }

  /** The staff user behind the request; Spring Security has already required SUPER_ADMIN. */
  static UUID userId(Caller caller) {
    if (caller == null || caller.userId() == null) {
      throw new BudgetInsightsException(ErrorCode.ADMIN_ROLE_REQUIRED);
    }
    return caller.userId();
  }

  /** {@code ?active=} on the lists: {@code true}, {@code false}, {@code all} or absent; else E.006. */
  static String activeFilter(String active) {
    if (active == null
        || active.isBlank()
        || active.equalsIgnoreCase("true")
        || active.equalsIgnoreCase("false")
        || active.equalsIgnoreCase("all")) {
      return active;
    }
    throw BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_FIELD_INVALID,
        "Some fields are not valid.",
        AdminFieldError.of("active", "must be true, false or all"));
  }

  /** An optional enum-like query value: absent/blank, or one of {@code allowed}; else E.006. */
  static String oneOf(String field, String value, String... allowed) {
    if (value == null || value.isBlank()) {
      return null;
    }
    for (String a : allowed) {
      if (a.equalsIgnoreCase(value.trim())) {
        return a;
      }
    }
    throw BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_FIELD_INVALID,
        "Some fields are not valid.",
        AdminFieldError.of(field, "must be one of " + String.join(", ", allowed)));
  }

  /** A 0-based page number; negative → E.006. */
  static int page(int page) {
    if (page < 0) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Some fields are not valid.",
          AdminFieldError.of("page", "must be 0 or more"));
    }
    return page;
  }

  /**
   * Like {@link #revision} but optional: absent means "no lost-update check". Only for the bank
   * edit routes, which took no If-Match before they became drafts, so existing callers keep working.
   */
  static Long optionalRevision(String ifMatch) {
    return ifMatch == null || ifMatch.isBlank() ? null : revision(ifMatch);
  }

  /**
   * Parses {@code If-Match: "12"} (quotes optional). Required on every configuration write, so a
   * client cannot skip the lost-update check: absent, blank or {@code *} → 409 {@code E.010}.
   */
  static Long revision(String ifMatch) {
    if (ifMatch == null || ifMatch.isBlank() || "*".equals(ifMatch.trim())) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_CHANGESET_STALE,
          "Send If-Match with the changeset revision you loaded (GET /config/changeset or"
              + " /config/summary).");
    }
    String v = ifMatch.trim();
    if (v.startsWith("W/")) {
      v = v.substring(2);
    }
    v = v.replace("\"", "");
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException ex) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID, "If-Match must be the changeset revision number.");
    }
  }
}
