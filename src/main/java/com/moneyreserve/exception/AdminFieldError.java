package com.moneyreserve.exception;

import java.util.List;

/**
 * One entry of {@code details[]} on a {@code MR.ADM.E.006} (or E.008) error body: which input
 * field was refused, and why.
 */
public record AdminFieldError(String field, String message) {

  /** {@code details} for a single refused field. */
  public static List<AdminFieldError> of(String field, String message) {
    return List.of(new AdminFieldError(field, message));
  }
}
