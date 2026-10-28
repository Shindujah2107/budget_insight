package com.moneyreserve.exception;

/**
 * The request is authenticated but carries no customer identity.
 *
 * <p>A defensive check: every route behind the bearer-token filter has a {@link
 * com.moneyreserve.config.Caller}. It answers 401 with error "Unauthorized".
 */
public class CallerUnidentifiedException extends RuntimeException {
  public CallerUnidentifiedException(String message) {
    super(message);
  }
}
