package com.moneyreserve.security;

import static com.moneyreserve.security.AdminTestSupport.data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneyreserve.domain.BiBudgetPolicy;
import com.moneyreserve.repository.BiBudgetPolicyRepository;
import com.moneyreserve.service.impl.BudgetPolicyProvider;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * The "What changed? — Premium Insights" switch in Admin › Spending › Budget policy (V1.28.0): a
 * draft until published, then live; recorded in the release history with who and when; admin
 * only; and it never grants Premium.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Admin: What changed? switch")
class WhatChangedAdminIT {

  private static final String POLICY =
      "{\"needsPct\":50,\"wantsPct\":30,\"savingsPct\":20,\"trailingMonths\":3,\"defaultAlertPct\":80,"
          + "\"paceTolerancePct\":0,\"alertCategoryApproaching\":true,\"alertCategoryExceeded\":true,"
          + "\"alertBudgetExceeded\":true";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiBudgetPolicyRepository policies;
  @Autowired private BudgetPolicyProvider provider;

  @AfterEach
  void off() {
    policies.findById(BiBudgetPolicy.ID).ifPresent(p -> {
      p.setWhatChangedOn(false);
      policies.saveAndFlush(p);
    });
    provider.evict();
  }

  private JsonNode availability(UUID customer) throws Exception {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(customer));
    ResponseEntity<String> r =
        rest.exchange("/api/insights/what-changed/availability", HttpMethod.GET, new HttpEntity<>(null, h), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  @Test
  @DisplayName("OFF by default; ON is a draft until published, then live and in the release history; it never grants Premium")
  void publishTurnsItOn() throws Exception {
    UUID customer = UUID.randomUUID();
    assertFalse(availability(customer).path("enabled").asBoolean(), "off by default");

    UUID staff = UUID.randomUUID();
    AdminTestSupport admin = new AdminTestSupport(rest, staff);
    admin.ensureOther();
    ResponseEntity<String> draft = admin.config(HttpMethod.PUT, "/budget-policy", POLICY + ",\"whatChanged\":true}");
    assertEquals(200, draft.getStatusCode().value(), draft.getBody());
    JsonNode item = data(draft).path("item");
    assertTrue(item.path("effective").path("whatChanged").asBoolean());
    assertFalse(item.path("published").path("whatChanged").asBoolean(), "not live before publish");
    assertFalse(availability(customer).path("enabled").asBoolean());

    ResponseEntity<String> published = admin.publishAll();
    assertEquals(201, published.getStatusCode().value(), published.getBody());
    int version = data(published).path("version").asInt();
    JsonNode a = availability(customer);
    assertTrue(a.path("enabled").asBoolean(), "live at once on this instance");
    assertFalse(a.path("entitled").asBoolean());
    assertFalse(a.path("available").asBoolean(), "ON never grants Premium");
    assertEquals("PREMIUM_REQUIRED", a.path("reason").asText());
    assertTrue(policies.findById(BiBudgetPolicy.ID).orElseThrow().getWhatChangedOn(), "persisted");

    // Audit: the release names who published it, when, and the switch's old and new value.
    JsonNode release = data(admin.config(HttpMethod.GET, "/releases/" + version, null));
    String diff = release.path("diff").toString();
    assertTrue(diff.contains("whatChanged"), release.toString());
    assertEquals(staff.toString(), release.path("publishedBy").path("staffId").asText(), release.toString());
    assertFalse(release.path("publishedAt").asText().isEmpty());
    assertTrue(diff.contains("\"whatChanged\":false") && diff.contains("\"whatChanged\":true"), "old and new value: " + diff);

    // OFF again (a policy saved without the field is off).
    admin.config(HttpMethod.PUT, "/budget-policy", POLICY + "}");
    assertEquals(201, admin.publishAll().getStatusCode().value());
    assertFalse(availability(customer).path("enabled").asBoolean());
  }

  @Test
  @DisplayName("only admins can change the switch")
  void adminOnly() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), "USER"));
    h.set("Content-Type", "application/json");
    ResponseEntity<String> r =
        rest.exchange(
            AdminTestSupport.CONFIG + "/budget-policy",
            HttpMethod.PUT,
            new HttpEntity<>(POLICY + ",\"whatChanged\":true}", h),
            String.class);
    assertEquals(403, r.getStatusCode().value());
  }
}
