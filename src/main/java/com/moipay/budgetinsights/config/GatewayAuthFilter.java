package com.moipay.budgetinsights.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Placed in the Spring Security chain when {@code
 * moipay.budget-insights.security.gateway-auth-enabled=true}.
 *
 * <p>The wording matters, and the previous wording was wrong. This class said it was
 * "active when" that flag is true, but a {@code Filter} bean is auto-registered against the
 * servlet container by Spring Boot whatever any flag says, so it ran with the flag off as well
 * and ran twice with it on. {@code SecurityConfig.gatewayAuthFilterRegistration} suppresses
 * that container registration, leaving the chain as the single place this filter is wired.
 *
 * <p>Mirrors CaseEngine / FeeEngine / SplitEngine: the gateway validates the caller's JWT, strips
 * the {@code Authorization} header, and injects the validated claims as {@code X-User-*} headers
 * plus a shared {@code X-Gateway-Token} secret. This filter rebuilds a Spring Security context from
 * those headers so {@code .authenticated()} succeeds for gateway-routed requests.
 *
 * <p><b>No scopes.</b> Core guarded these endpoints with {@code SCOPE_INSIGHTS_READ} and
 * {@code SCOPE_INSIGHTS_TXN_WRITE}, resolved by querying core's {@code rbac} schema. This service
 * has no such schema and no way to resolve a scope, so — as in the three sibling services — the
 * control is ownership scoping inside every repository query, not a role or scope tier. Every
 * Budget Insights finder already takes the owner; none can be called without one.
 *
 * <p>Two differences from SplitEngine, both deliberate: {@code X-Tenant} is read as well, because
 * every row this service writes carries a tenant; and a request whose {@code X-User-Id} is not a
 * UUID is left unauthenticated rather than admitted with a principal nothing can use.
 */
@Slf4j
@Component
public class GatewayAuthFilter extends OncePerRequestFilter {

  /**
   * The shared secret the gateway presents.
   *
   * <p>No default. A literal fallback here meant a service with no secret configured still
   * accepted a token, and the token was readable in this file. Blank is handled below: a
   * request carrying a gateway token is passed through unauthenticated and the misconfiguration
   * is logged, so the failure is visible rather than silently permissive.
   */
  @Value("${moipay.budget-insights.security.gateway-internal-token:}")
  private String expectedGatewayToken;

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {

    String gatewayToken = req.getHeader("X-Gateway-Token");

    if (gatewayToken == null) {
      chain.doFilter(req, res);
      return;
    }

    if (expectedGatewayToken == null || expectedGatewayToken.isBlank()) {
      log.error(
          "[gateway-auth] X-Gateway-Token received but this service has NO expected token "
              + "configured (moipay.budget-insights.security.gateway-internal-token is blank) — "
              + "populate the GATEWAY_INTERNAL_TOKEN secret. Rejecting {} {}",
          req.getMethod(),
          req.getRequestURI());
      chain.doFilter(req, res);
      return;
    }

    if (!expectedGatewayToken.equals(gatewayToken)) {
      log.error(
          "[gateway-auth] X-Gateway-Token MISMATCH on {} {} (received len={}, expected len={}) — "
              + "the authorizer's GATEWAY_INTERNAL_TOKEN and this service's secret differ. "
              + "Passing through unauthenticated.",
          req.getMethod(),
          req.getRequestURI(),
          gatewayToken.length(),
          expectedGatewayToken.length());
      chain.doFilter(req, res);
      return;
    }

    String userId = req.getHeader("X-User-Id");
    String tenant = req.getHeader("X-Tenant");
    String rolesHeader = req.getHeader("X-User-Roles");

    if (userId != null && !userId.isBlank()) {
      UUID uid = parseUuid(userId, "X-User-Id", req);
      if (uid == null) {
        chain.doFilter(req, res);
        return;
      }
      // Tenant may legitimately be absent for a platform-admin caller with no tenant claim.
      UUID tid = (tenant == null || tenant.isBlank()) ? null : parseUuid(tenant, "X-Tenant", req);

      List<GrantedAuthority> authorities = new ArrayList<>();
      if (rolesHeader != null && !rolesHeader.isBlank()) {
        authorities =
            Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toList());
      }
      if (authorities.isEmpty()) {
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
      }

      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(new Caller(uid, tid), null, authorities));

    } else {
      log.info(
          "[gateway-auth] gateway token matched but X-User-Id missing on {} {}",
          req.getMethod(),
          req.getRequestURI());
    }

    chain.doFilter(req, res);
  }

  private UUID parseUuid(String raw, String header, HttpServletRequest req) {
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException ex) {
      log.info(
          "[gateway-auth] {} is not a UUID on {} {} — leaving the request unauthenticated",
          header,
          req.getMethod(),
          req.getRequestURI());
      return null;
    }
  }
}
