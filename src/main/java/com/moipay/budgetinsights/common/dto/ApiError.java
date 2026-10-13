package com.moipay.budgetinsights.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Error envelope, identical in shape to SplitEngine's.
 *
 * <p>{@code error} carries the Budget Insights code — {@code MOI.BI.TXN.E.001} and friends —
 * exactly as MoiPay-Backend returned it before the migration, so a client that was reading the code
 * out of the error field keeps working unchanged.
 */
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    @JsonInclude(JsonInclude.Include.NON_NULL) Object details) {

  public static ApiError of(int status, String error, String message, String path) {
    return new ApiError(Instant.now(), status, error, message, path, List.of());
  }

  /**
   * {@code details} is a list of strings on the customer endpoints and a structured object on the
   * admin endpoints; {@code null} leaves the field out of the body.
   */
  public static ApiError of(
      int status, String error, String message, String path, Object details) {
    return new ApiError(Instant.now(), status, error, message, path, details);
  }
}
