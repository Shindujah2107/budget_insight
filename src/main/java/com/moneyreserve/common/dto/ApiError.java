package com.moneyreserve.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Error envelope.
 *
 * <p>{@code error} carries the MoneyReserve error code — {@code MR.TXN.E.001} and friends — which
 * the app reads to choose the message it shows.
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
