package com.moneyreserve.exception;

import java.util.List;
import lombok.Getter;

/**
 * A refusal the service intends, carrying the code the client will see.
 *
 * <p>One exception type with a code enum, rather than one type per status, because the code is what
 * the app reads.
 */
@Getter
public class BudgetInsightsException extends RuntimeException {

  private final ErrorCode errorCode;
  private final Long retryAfterSeconds;

  /** Short lines shown under the message (admin validation results, usage). Never request data. */
  /**
   * Extra detail for the error body: a list of strings on the customer endpoints, or a structured
   * object on the admin endpoints ({@code [{field, message}]}, {@code {usage}}, {@code {errors}} …).
   */
  private final Object details;

  public BudgetInsightsException(ErrorCode errorCode) {
    this(errorCode, null, null);
  }

  public BudgetInsightsException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  public BudgetInsightsException(ErrorCode errorCode, String message, Long retryAfterSeconds) {
    this(errorCode, message, retryAfterSeconds, List.of());
  }

  /** A refusal carrying short explanatory lines (used by the admin API). */
  public static BudgetInsightsException withDetails(
      ErrorCode errorCode, String message, Object details) {
    return new BudgetInsightsException(errorCode, message, null, details);
  }

  private BudgetInsightsException(
      ErrorCode errorCode, String message, Long retryAfterSeconds, Object details) {
    super(message != null && !message.isBlank() ? message : errorCode.getDefaultMessage());
    this.errorCode = errorCode;
    this.retryAfterSeconds = retryAfterSeconds;
    this.details = details == null ? List.of() : details instanceof List<?> l ? List.copyOf(l) : details;
  }
}
