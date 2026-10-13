package com.moipay.budgetinsights.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests that reach the API Gateway directly, bypassing CloudFront (and the WAF).
 * CloudFront injects a secret {@code X-Origin-Verify} header on every origin request; requests that
 * did not come through CloudFront do not carry it. The API is an API Gateway HTTP API (v2), which
 * cannot have WAF attached directly — fronting it with CloudFront + enforcing this header is what
 * makes the WAF effective.
 *
 * <p>Two-phase rollout via {@code moipay.security.origin-verify.enforce}: enforce=false is log-only
 * (logs but does not block, to confirm CloudFront traffic carries the header); enforce=true returns
 * 403 on a missing/invalid header.
 *
 * <p>Health/actuator paths are exempt (ALB probes hit the service directly without the header). A
 * request with a valid {@code X-Gateway-Token} (the existing service-to-service secret, see {@link
 * GatewayAuthFilter}) is also accepted in place of the CloudFront header — that header already
 * proves the caller is a genuine internal service (e.g. core -> split-engine over the internal ALB)
 * rather than a direct-gateway bypass of CloudFront.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OriginVerifyFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Origin-Verify";
  private static final String GATEWAY_TOKEN_HEADER = "X-Gateway-Token";

  @Value("${moipay.security.origin-verify.enabled:false}")
  private boolean enabled;

  @Value("${moipay.security.origin-verify.enforce:false}")
  private boolean enforce;

  @Value("${moipay.security.origin-verify.secret:}")
  private String expectedSecret;

  @Value("${moipay.budget-insights.security.gateway-internal-token:}")
  private String expectedGatewayToken;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    if (!enabled) {
      return true;
    }
    String uri = req.getRequestURI();
    return uri != null && (uri.contains("/actuator") || uri.endsWith("/health"));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {

    if (expectedSecret == null || expectedSecret.isBlank()) {
      log.error("[origin-verify] enabled but no secret configured — skipping check");
      chain.doFilter(req, res);
      return;
    }

    String provided = req.getHeader(HEADER);
    if (expectedSecret.equals(provided)) {
      chain.doFilter(req, res);
      return;
    }

    String gatewayToken = req.getHeader(GATEWAY_TOKEN_HEADER);
    if (expectedGatewayToken != null
        && !expectedGatewayToken.isBlank()
        && expectedGatewayToken.equals(gatewayToken)) {
      chain.doFilter(req, res);
      return;
    }

    if (enforce) {
      log.info(
          "[origin-verify] BLOCKED direct (non-CloudFront) request {} {} (header {})",
          req.getMethod(),
          req.getRequestURI(),
          provided == null ? "absent" : "mismatch");
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      res.setContentType("application/json");
      res.getWriter()
          .write("{\"status\":403,\"error\":\"FORBIDDEN\",\"message\":\"Direct access is not allowed.\"}");
      return;
    }

    log.info(
        "[origin-verify] LOG-ONLY: request {} {} missing/invalid {} (header {}) — would be blocked when enforce=true",
        req.getMethod(),
        req.getRequestURI(),
        HEADER,
        provided == null ? "absent" : "mismatch");
    chain.doFilter(req, res);
  }
}
