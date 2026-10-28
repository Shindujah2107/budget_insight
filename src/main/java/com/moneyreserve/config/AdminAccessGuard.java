package com.moneyreserve.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.common.dto.ApiError;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.ingest.IngestRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The admin API's feature switches, applied after Spring Security has admitted a Super Admin.
 *
 * <ol>
 *   <li>Master switch ({@code moneyreserve.insights.enabled}) off → 404. The admin API has no
 *       switch of its own: it is always on wherever Budget Insights runs.
 *   <li>{@code admin.config-write-enabled} off and the handler is an {@link AdminWrite} → 403
 *       {@code MR.ADM.E.004}. Reads keep working.
 *   <li>More than {@code admin.writes-per-minute} (default 60) writes from one Super Admin in a
 *       minute → 429 {@code MR.ADM.E.022} with {@code Retry-After}; {@link AdminRateLimit}
 *       routes have their own budgets (rule tests 30, builder calls 120 per minute).
 * </ol>
 *
 * <p>It also writes the one structured INFO line per admin request (user, roles, route, status,
 * duration; never a body) and counts {@code bi_admin_requests_total{route,status}}.
 *
 * <p>Same shape as {@link ManualEntryGate}: an interceptor, so the controllers stay free of flag
 * checks and a new admin route cannot forget them.
 */
@Slf4j
@Component
public class AdminAccessGuard implements HandlerInterceptor {

  private static final String STARTED = AdminAccessGuard.class.getName() + ".started";

  private final InsightsProperties properties;
  private final ObjectMapper objectMapper;
  private final ObjectProvider<MeterRegistry> meters;
  private final IngestRateLimiter writeLimiter;
  private final Map<AdminRateLimit.Bucket, IngestRateLimiter> bucketLimiters =
      new EnumMap<>(AdminRateLimit.Bucket.class);

  public AdminAccessGuard(
      InsightsProperties properties,
      ObjectMapper objectMapper,
      ObjectProvider<MeterRegistry> meters) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.meters = meters;
    this.writeLimiter =
        new IngestRateLimiter(properties.getAdmin().getWritesPerMinute(), Duration.ofMinutes(1));
    bucketLimiters.put(
        AdminRateLimit.Bucket.RULE_TEST,
        new IngestRateLimiter(properties.getAdmin().getRuleTestsPerMinute(), Duration.ofMinutes(1)));
    bucketLimiters.put(
        AdminRateLimit.Bucket.BUILDER,
        new IngestRateLimiter(properties.getAdmin().getBuilderCallsPerMinute(), Duration.ofMinutes(1)));
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    request.setAttribute(STARTED, System.nanoTime());
    if (!properties.isEnabled()) {
      log.info(
          "bi-admin request refused: Budget Insights is switched off {} {}",
          request.getMethod(),
          request.getRequestURI());
      return refuse(
          response,
          HttpStatus.NOT_FOUND,
          ApiError.of(
              404,
              "Not Found",
              "Budget Insights is not available in this environment.",
              request.getRequestURI(),
              null));
    }
    boolean write =
        handler instanceof HandlerMethod method && method.hasMethodAnnotation(AdminWrite.class);
    if (write && !properties.getAdmin().isConfigWriteEnabled()) {
      return refuse(response, ErrorCode.ADMIN_CONFIG_WRITE_DISABLED, request, null);
    }
    if (write) {
      UUID user = userId();
      Instant now = Instant.now();
      if (!writeLimiter.tryConsume(user, 1, now)) {
        return refuse(
            response,
            ErrorCode.ADMIN_RATE_LIMITED,
            request,
            writeLimiter.retryAfterSeconds(user, now));
      }
      writeLimiter.evictExpired(now);
    }
    AdminRateLimit limited =
        handler instanceof HandlerMethod method ? method.getMethodAnnotation(AdminRateLimit.class) : null;
    if (limited != null) {
      IngestRateLimiter limiter = bucketLimiters.get(limited.value());
      UUID user = userId();
      Instant now = Instant.now();
      if (!limiter.tryConsume(user, 1, now)) {
        return refuse(
            response, ErrorCode.ADMIN_RATE_LIMITED, request, limiter.retryAfterSeconds(user, now));
      }
      limiter.evictExpired(now);
    }
    return true;
  }

  /** One INFO line per admin request, no bodies (architecture doc §1.7). */
  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    Object started = request.getAttribute(STARTED);
    long ms =
        started instanceof Long t ? Duration.ofNanos(System.nanoTime() - t).toMillis() : -1L;
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String route = request.getMethod() + " " + (pattern == null ? "unmapped" : pattern);
    int status = response.getStatus();
    log.info(
        "bi-admin request user={} roles={} route=\"{}\" status={} durationMs={}",
        userId(),
        roles(),
        route,
        status,
        ms);
    MeterRegistry registry = meters.getIfAvailable();
    if (registry != null) {
      registry
          .counter("bi_admin_requests_total", "route", route, "status", String.valueOf(status))
          .increment();
    }
  }

  private static UUID userId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Caller c ? c.userId() : null;
  }

  private static String roles() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null
        ? ""
        : auth.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.joining(","));
  }

  private boolean refuse(
      HttpServletResponse response, ErrorCode code, HttpServletRequest request, Long retryAfter)
      throws IOException {
    log.info(
        "bi-admin request refused user={} {} {} code={} retryAfterSeconds={}",
        userId(),
        request.getMethod(),
        request.getRequestURI(),
        code.getCode(),
        retryAfter);
    if (retryAfter != null) {
      response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    }
    return refuse(
        response,
        code.getHttpStatus(),
        ApiError.of(
            code.getHttpStatus().value(),
            code.getCode(),
            code.getDefaultMessage(),
            request.getRequestURI(),
            null));
  }

  private boolean refuse(HttpServletResponse response, HttpStatus status, ApiError body)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), body);
    return false;
  }
}
