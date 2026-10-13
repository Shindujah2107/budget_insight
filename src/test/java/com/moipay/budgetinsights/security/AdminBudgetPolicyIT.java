package com.moipay.budgetinsights.security;

import static com.moipay.budgetinsights.security.AdminTestSupport.data;
import static com.moipay.budgetinsights.security.AdminTestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Budget policy (/admin/config/budget-policy): read, validated draft, discard. The test schema has
 * no policy row, so the live values are the configured defaults (the V1.9.0 seed values).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: budget policy")
class AdminBudgetPolicyIT {

  private static final String SEED =
      "{\"needsPct\":50,\"wantsPct\":30,\"savingsPct\":20,\"trailingMonths\":3,\"defaultAlertPct\":80,"
          + "\"paceTolerancePct\":0,\"alertCategoryApproaching\":true,\"alertCategoryExceeded\":true,"
          + "\"alertBudgetExceeded\":true}";

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("the policy is readable, with the configured defaults live and no draft")
  void readsDefaults() throws Exception {
    JsonNode d = data(new AdminTestSupport(rest, UUID.randomUUID()).config(HttpMethod.GET, "/budget-policy", null));
    assertEquals("PUBLISHED", d.path("state").asText(), d.toString());
    assertEquals(50, d.path("published").path("needsPct").asInt());
    assertEquals(80, d.path("published").path("defaultAlertPct").asInt());
    assertEquals(3, d.path("published").path("trailingMonths").asInt());
    assertTrue(d.path("changesetRevision").isNumber());
  }

  @Test
  @DisplayName("a split that does not add up to 100, or a value out of range, is refused")
  void validates() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    ResponseEntity<String> sum = admin.config(HttpMethod.PUT, "/budget-policy", SEED.replace("\"needsPct\":50", "\"needsPct\":55"));
    assertEquals(400, sum.getStatusCode().value(), sum.getBody());
    assertEquals("MOI.BI.ADM.E.006", json(sum).path("error").asText());
    ResponseEntity<String> alert = admin.config(HttpMethod.PUT, "/budget-policy", SEED.replace("\"defaultAlertPct\":80", "\"defaultAlertPct\":40"));
    assertEquals(400, alert.getStatusCode().value());
    ResponseEntity<String> months = admin.config(HttpMethod.PUT, "/budget-policy", SEED.replace("\"trailingMonths\":3", "\"trailingMonths\":13"));
    assertEquals(400, months.getStatusCode().value());
  }

  @Test
  @DisplayName("a valid change is a draft until published, and can be discarded")
  void draftAndDiscard() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String changed =
        SEED.replace("\"defaultAlertPct\":80", "\"defaultAlertPct\":75").replace("\"trailingMonths\":3", "\"trailingMonths\":6");
    ResponseEntity<String> saved = admin.config(HttpMethod.PUT, "/budget-policy", changed);
    assertEquals(200, saved.getStatusCode().value(), saved.getBody());
    JsonNode item = data(saved).path("item");
    assertEquals("DRAFT_EDITED", item.path("state").asText());
    assertEquals(75, item.path("effective").path("defaultAlertPct").asInt());
    assertEquals(80, item.path("published").path("defaultAlertPct").asInt(), "not live before publish");

    ResponseEntity<String> discarded = admin.config(HttpMethod.DELETE, "/budget-policy/draft", null);
    assertEquals(200, discarded.getStatusCode().value(), discarded.getBody());
    assertEquals("PUBLISHED", data(discarded).path("item").path("state").asText());
  }

  @Test
  @DisplayName("a customer token cannot read or change the policy")
  void adminOnly() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Gateway-Token", "integration-test-gateway-token");
    headers.add("X-User-Id", UUID.randomUUID().toString());
    headers.add("X-User-Roles", "USER");
    ResponseEntity<String> r =
        rest.exchange(
            AdminTestSupport.CONFIG + "/budget-policy",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            String.class);
    assertEquals(403, r.getStatusCode().value());
  }
}
