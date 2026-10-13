package com.moipay.budgetinsights.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The migration's riskiest new code.
 *
 * <p>Everything else was ported with its behaviour intact; this filter is the one piece written
 * from scratch, and it is what decides whose ledger a request can reach. A defect here is not a
 * wrong number on a screen — it is one customer seeing another's spending.
 */
class GatewayAuthFilterTest {

  private static final String SECRET = "test-gateway-secret";

  private final GatewayAuthFilter filter = new GatewayAuthFilter();
  private final FilterChain chain = Mockito.mock(FilterChain.class);

  GatewayAuthFilterTest() {
    ReflectionTestUtils.setField(filter, "expectedGatewayToken", SECRET);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private Authentication run(MockHttpServletRequest req) throws Exception {
    filter.doFilterInternal(req, new MockHttpServletResponse(), chain);
    return SecurityContextHolder.getContext().getAuthentication();
  }

  private MockHttpServletRequest gatewayRequest(String token, String userId, String tenant) {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/breakdown");
    if (token != null) {
      req.addHeader("X-Gateway-Token", token);
    }
    if (userId != null) {
      req.addHeader("X-User-Id", userId);
    }
    if (tenant != null) {
      req.addHeader("X-Tenant", tenant);
    }
    req.addHeader("X-User-Roles", "TENANT_ADMIN");
    return req;
  }

  @Test
  @DisplayName("a valid gateway request authenticates with a Caller carrying user and tenant")
  void authenticates() throws Exception {
    UUID user = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();

    Authentication auth = run(gatewayRequest(SECRET, user.toString(), tenant.toString()));

    assertInstanceOf(Caller.class, auth.getPrincipal());
    Caller caller = (Caller) auth.getPrincipal();
    assertEquals(user, caller.userId());
    assertEquals(tenant, caller.tenantId());
    assertTrue(
        auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TENANT_ADMIN")));
  }

  @Test
  @DisplayName("no gateway token means no authentication — the request is not ours to trust")
  void noToken() throws Exception {
    assertNull(run(gatewayRequest(null, UUID.randomUUID().toString(), null)));
  }

  @Test
  @DisplayName("a wrong gateway token is refused, not merely logged")
  void wrongToken() throws Exception {
    assertNull(run(gatewayRequest("not-the-secret", UUID.randomUUID().toString(), null)));
  }

  @Test
  @DisplayName("a non-UUID X-User-Id leaves the request unauthenticated")
  void malformedUserId() throws Exception {
    assertNull(run(gatewayRequest(SECRET, "not-a-uuid", null)));
  }

  @Test
  @DisplayName("a missing tenant still authenticates — platform admins carry no tenant claim")
  void tenantOptional() throws Exception {
    UUID user = UUID.randomUUID();
    Authentication auth = run(gatewayRequest(SECRET, user.toString(), null));

    Caller caller = (Caller) auth.getPrincipal();
    assertEquals(user, caller.userId());
    assertNull(caller.tenantId());
  }

  @Test
  @DisplayName("a caller with no roles still gets ROLE_USER, never an empty authority list")
  void defaultRole() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/insights/breakdown");
    req.addHeader("X-Gateway-Token", SECRET);
    req.addHeader("X-User-Id", UUID.randomUUID().toString());

    Authentication auth = run(req);
    assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
  }
}
