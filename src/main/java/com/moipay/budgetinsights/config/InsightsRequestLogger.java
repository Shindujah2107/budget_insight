package com.moipay.budgetinsights.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One log line per customer (mobile) API request: user, route pattern, status, duration. The
 * customer-side counterpart of the {@code bi-admin request} line written by {@link
 * AdminAccessGuard}, so "did the phone call us, and what did it get back" can be answered from the
 * logs for every route (sync, ingest, budgets, consent, transactions, alerts, compliance).
 *
 * <p>Never a body, a query string or a header value: the route is the mapped pattern ({@code
 * /api/insights/transactions/{id}}), not the raw URI. INFO below 500, ERROR from 500 up.
 *
 * <p>Requests refused by Spring Security before reaching a handler (401/403) do not pass through
 * interceptors; those are logged by the security entry point and access-denied handler.
 */
@Slf4j
@Component
public class InsightsRequestLogger implements HandlerInterceptor {

  private static final String STARTED = InsightsRequestLogger.class.getName() + ".started";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    request.setAttribute(STARTED, System.nanoTime());
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
    Object started = request.getAttribute(STARTED);
    long ms = started instanceof Long t ? Duration.ofNanos(System.nanoTime() - t).toMillis() : -1L;
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    String route = request.getMethod() + " " + (pattern == null ? "unmapped" : pattern);
    int status = response.getStatus();
    if (status >= 500 || ex != null) {
      log.error(
          "bi-api request user={} route=\"{}\" status={} durationMs={}",
          userId(),
          route,
          status,
          ms,
          ex);
    } else {
      log.info(
          "bi-api request user={} route=\"{}\" status={} durationMs={}", userId(), route, status, ms);
    }
  }

  private static UUID userId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Caller c ? c.userId() : null;
  }
}
