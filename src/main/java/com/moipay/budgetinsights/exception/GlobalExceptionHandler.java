package com.moipay.budgetinsights.exception;

import com.moipay.budgetinsights.common.dto.ApiError;
import com.moipay.budgetinsights.config.AdminRoles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  /**
   * A refusal the service meant to make.
   *
   * <p>Logged at INFO, not ERROR. Nothing has failed: the service declined correctly. Core logged
   * every one of these at ERROR, which filled its error dashboard with customers being told no
   * exactly as designed; that is not carried over.
   */
  @ExceptionHandler(BudgetInsightsException.class)
  public ResponseEntity<ApiError> handleBudgetInsights(
      BudgetInsightsException ex, HttpServletRequest req) {

    ErrorCode code = ex.getErrorCode();
    if (code.getHttpStatus().is5xxServerError()) {
      log.error(
          "Budget Insights failure {} {} code={} status={} message={}",
          req.getMethod(),
          req.getRequestURI(),
          code.getCode(),
          code.getHttpStatus().value(),
          ex.getMessage(),
          ex);
    } else {
      log.info(
          "Budget Insights refusal {} {} code={} status={} message={}",
          req.getMethod(),
          req.getRequestURI(),
          code.getCode(),
          code.getHttpStatus().value(),
          ex.getMessage());
    }

    ResponseEntity.BodyBuilder response = ResponseEntity.status(code.getHttpStatus());
    if (ex.getRetryAfterSeconds() != null && ex.getRetryAfterSeconds() >= 0) {
      response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
    }
    return response.body(
        ApiError.of(
            code.getHttpStatus().value(),
            code.getCode(),
            ex.getMessage(),
            req.getRequestURI(),
            adminBodyDetails(code, ex.getDetails())));
  }

  /** Admin error bodies omit {@code details} when there are none (architecture doc §3.1). */
  private static Object adminBodyDetails(ErrorCode code, Object details) {
    boolean admin = code.getCode().startsWith(ADMIN_CODE_PREFIX);
    if (admin && details instanceof List<?> list && list.isEmpty()) {
      return null;
    }
    return details;
  }

  private static final String ADMIN_CODE_PREFIX = "MOI.BI.ADM.";

  private static boolean isAdminRoute(HttpServletRequest req) {
    String uri = req.getRequestURI();
    return uri != null && uri.contains(AdminRoles.BASE_PATH + "/");
  }

  /** An admin request whose input was refused before it reached a service: E.006. */
  private static ResponseEntity<ApiError> adminFieldInvalid(
      HttpServletRequest req, String message, List<AdminFieldError> details) {
    ErrorCode code = ErrorCode.ADMIN_FIELD_INVALID;
    log.info(
        "Budget Insights refusal {} {} code={} fields={}",
        req.getMethod(),
        req.getRequestURI(),
        code.getCode(),
        details.stream().map(AdminFieldError::field).toList());
    return ResponseEntity.status(code.getHttpStatus())
        .body(
            ApiError.of(
                code.getHttpStatus().value(),
                code.getCode(),
                message,
                req.getRequestURI(),
                details.isEmpty() ? null : details));
  }

  @ExceptionHandler(CallerUnidentifiedException.class)
  public ResponseEntity<ApiError> handleUnidentified(
      CallerUnidentifiedException ex, HttpServletRequest req) {
    log.info("Insights request without a customer identity on {}", req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiError.of(401, "Unauthorized", ex.getMessage(), req.getRequestURI()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    if (isAdminRoute(req)) {
      List<AdminFieldError> fields = new ArrayList<>();
      for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
        fields.add(new AdminFieldError(fe.getField(), fe.getDefaultMessage()));
      }
      ex.getBindingResult()
          .getGlobalErrors()
          .forEach(ge -> fields.add(new AdminFieldError(ge.getObjectName(), ge.getDefaultMessage())));
      return adminFieldInvalid(req, "Some fields are not valid.", fields);
    }
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream().map(FieldError::getDefaultMessage).toList();
    // Field names only: the rejected values can be customer data.
    log.info(
        "Request body validation failed {} {} fields={}",
        req.getMethod(),
        req.getRequestURI(),
        ex.getBindingResult().getFieldErrors().stream().map(FieldError::getField).distinct().toList());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ApiError.of(
                400,
                "Validation Failed",
                "Request body has invalid fields",
                req.getRequestURI(),
                details));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArg(
      IllegalArgumentException ex, HttpServletRequest req) {
    log.info("Bad request {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiError.of(400, "Bad Request", ex.getMessage(), req.getRequestURI()));
  }

  /** Reference data missing, a pattern that will not compile — a deployment defect, not a refusal. */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> handleIllegalState(
      IllegalStateException ex, HttpServletRequest req) {
    log.error("Budget Insights configuration or reference-data defect", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiError.of(
                500, "Internal Server Error", "Budget Insights is misconfigured.",
                req.getRequestURI()));
  }

  /**
   * Input Spring could not read, bind or convert.
   *
   * <p><b>Why these are declared explicitly.</b> This class is a plain {@code @RestControllerAdvice}
   * rather than a subclass of {@code ResponseEntityExceptionHandler}, and
   * {@code ExceptionHandlerExceptionResolver} runs ahead of {@code DefaultHandlerExceptionResolver}
   * in the resolver chain. That means the {@code Exception} catch-all below would otherwise claim
   * every one of these before the framework ever saw them, and a client that sent
   * {@code ?from=yesterday} or a UUID with a typo in it would get a 500 — logged at ERROR, with a
   * stack trace, on an error dashboard the {@code BudgetInsightsException} handler above went out
   * of its way to keep clean.
   *
   * <p>Logged at INFO for the same reason that handler is: a malformed request is the client being
   * wrong, not the service failing.
   */
  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiError> handleUnreadableRequest(
      Exception ex, HttpServletRequest req) {

    if (isAdminRoute(req)) {
      String field =
          ex instanceof MethodArgumentTypeMismatchException m
              ? m.getName()
              : ex instanceof MissingServletRequestParameterException p ? p.getParameterName() : "body";
      String why =
          ex instanceof MissingServletRequestParameterException
              ? "is required"
              : ex instanceof MethodArgumentTypeMismatchException
                  ? "has the wrong format"
                  : "could not be read as JSON of the expected shape";
      return adminFieldInvalid(
          req, "That request could not be read.", AdminFieldError.of(field, why));
    }

    log.info(
        "Malformed request on {} {} ({})",
        req.getMethod(),
        req.getRequestURI(),
        ex.getClass().getSimpleName());
    // Deliberately not ex.getMessage(): the framework's text names parameter types and target
    // classes, which is internal shape on the wire for no benefit to the caller.
    return ResponseEntity.badRequest()
        .body(
            ApiError.of(
                400, "Bad Request", "That request could not be read.", req.getRequestURI()));
  }

  /**
   * A constraint on a method parameter, as opposed to one inside a {@code @RequestBody}.
   *
   * <p>Raised by the {@code @Validated} controllers when a {@code @RequestParam} or
   * {@code @PathVariable} carries its own constraint — for example the size bound on
   * {@code deviceId} at reference sync. Without this handler such a violation is a 500.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiError> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest req) {

    if (isAdminRoute(req)) {
      List<AdminFieldError> fields = new ArrayList<>();
      if (ex.getConstraintViolations() != null) {
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
          String path = String.valueOf(v.getPropertyPath());
          fields.add(new AdminFieldError(path.substring(path.lastIndexOf('.') + 1), v.getMessage()));
        }
      }
      return adminFieldInvalid(req, "Some fields are not valid.", fields);
    }
    List<String> details = new ArrayList<>();
    if (ex.getConstraintViolations() != null) {
      for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
        details.add(violation.getMessage());
      }
    }
    log.info("Parameter validation failed on {} {}", req.getMethod(), req.getRequestURI());
    return ResponseEntity.badRequest()
        .body(
            ApiError.of(
                400,
                "Validation Failed",
                "Please check the values sent with this request.",
                req.getRequestURI(),
                details));
  }

  /**
   * A path this service does not serve.
   *
   * <p>Spring 6 raises {@link NoResourceFoundException} for an unmapped path, and without this
   * handler it fell through to the generic handler: every mistyped URL answered 500 and wrote a
   * stack trace at ERROR. Found in DEV acceptance testing on a request for the bare
   * {@code /insights/v3/api-docs}. It is the caller asking for something that does not exist, so
   * 404, logged at INFO.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiError> handleNoResource(
      NoResourceFoundException ex, HttpServletRequest req) {

    log.info("No such path {} {}", req.getMethod(), req.getRequestURI());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            ApiError.of(
                404, "Not Found", "There is nothing at this address.", req.getRequestURI()));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiError> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {

    log.info("Method {} not supported on {}", req.getMethod(), req.getRequestURI());
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(
            ApiError.of(
                405,
                "Method Not Allowed",
                "That method is not supported on this endpoint.",
                req.getRequestURI()));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ApiError> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {

    log.info("Unsupported media type on {} {}", req.getMethod(), req.getRequestURI());
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(
            ApiError.of(
                415,
                "Unsupported Media Type",
                "This endpoint accepts application/json.",
                req.getRequestURI()));
  }

  /**
   * A write the database refused.
   *
   * <p>409 rather than 500 because every reachable case is a property of the request rather than a
   * fault in the service: a uniqueness collision that survived its retry, or a column constraint
   * the request's own values violate. Logged at WARN — not ERROR, because it is not an outage, and
   * not INFO, because a constraint reaching the database means a guard above it did not fire and
   * somebody should look.
   *
   * <p>The cause is logged but never returned: constraint names are schema detail.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleDataIntegrity(
      DataIntegrityViolationException ex, HttpServletRequest req) {

    log.error(
        "Database refused a write on {} {}: {}",
        req.getMethod(),
        req.getRequestURI(),
        ex.getMostSpecificCause().getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiError.of(
                409,
                "Conflict",
                "That change conflicts with data already held. Please try again.",
                req.getRequestURI()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
    log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            ApiError.of(
                500, "Internal Server Error", "Something went wrong.", req.getRequestURI()));
  }
}
