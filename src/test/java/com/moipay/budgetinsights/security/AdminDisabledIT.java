package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The admin API has no enable switch of its own: it is always on wherever Budget Insights runs.
 * A leftover {@code moipay.budget-insights.admin.enabled=false} must not switch it off, and must
 * not open it to other roles. (The master switch still hides it: {@code MasterSwitchIT}.)
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moipay.budget-insights.admin.enabled=false")
@ActiveProfiles("test")
@DisplayName("Budget Insights admin is always on")
class AdminDisabledIT {

  @Autowired private TestRestTemplate rest;

  private static HttpEntity<String> as(String roles) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Gateway-Token", "integration-test-gateway-token");
    headers.add("X-User-Id", UUID.randomUUID().toString());
    headers.add("X-Tenant", UUID.randomUUID().toString());
    headers.add("X-User-Roles", roles);
    return new HttpEntity<>(null, headers);
  }

  @Test
  @DisplayName("admin.enabled=false is ignored: the Overview answers 200 to a Super Admin")
  void overviewIsPresent() {
    ResponseEntity<String> response =
        rest.exchange("/api/insights/admin/overview", HttpMethod.GET, as("SUPER_ADMIN"), String.class);
    assertEquals(200, response.getStatusCode().value(), response.getBody());
  }

  @Test
  @DisplayName("other roles still get 403")
  void otherRolesStillRefused() {
    ResponseEntity<String> response =
        rest.exchange("/api/insights/admin/overview", HttpMethod.GET, as("TENANT_ADMIN"), String.class);
    assertEquals(403, response.getStatusCode().value());
  }
}
