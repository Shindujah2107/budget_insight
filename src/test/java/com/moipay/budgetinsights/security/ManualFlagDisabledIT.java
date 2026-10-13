package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The R1 manual-entry release gate, switched off.
 *
 * <p>DIP line 1648 names {@code manual.enabled} and the service shipped without it. Every other
 * release could be withdrawn on its own; entry by hand could not, so withdrawing it meant
 * withdrawing Budget Insights entirely. These tests fix the meaning of "off": the three write
 * routes answer 501, and nothing a customer can read about their existing money changes.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moipay.budget-insights.manual.enabled=false")
@ActiveProfiles("test")
@DisplayName("Manual entry gate — disabled")
class ManualFlagDisabledIT {

  @Autowired private TestRestTemplate rest;

  private static final String TRANSACTIONS = "/api/insights/transactions";

  private static HttpEntity<String> authed(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Gateway-Token", "integration-test-gateway-token");
    headers.add("X-User-Id", UUID.randomUUID().toString());
    headers.add("X-Tenant", UUID.randomUUID().toString());
    return new HttpEntity<>(body, headers);
  }

  @Test
  @DisplayName("Creating by hand answers 501")
  void createRefused() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS,
            HttpMethod.POST,
            authed("{\"amount\":100.00,\"direction\":\"DEBIT\"}"),
            String.class);

    // 501, not 404. 404 is what the master switch says -- the Insights tab is absent. 501 says
    // the tab is here and this one capability is withdrawn, which is what every other release
    // gate in this service says when its flag is off.
    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }

  @Test
  @DisplayName("Editing by hand answers 501, which covers re-categorisation")
  void updateRefused() {
    // Re-categorising is a PATCH on the transaction, so this is the gate for it too.
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + UUID.randomUUID(),
            HttpMethod.PATCH,
            authed("{\"categoryId\":\"" + UUID.randomUUID() + "\"}"),
            String.class);

    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }

  @Test
  @DisplayName("Deleting by hand answers 501")
  void deleteRefused() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + UUID.randomUUID(), HttpMethod.DELETE, authed(null), String.class);

    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }

  @Test
  @DisplayName("Reading is untouched — a customer can still see what they already have")
  void readsUnaffected() {
    // The gate is write access by hand and nothing else. Hiding a customer's existing
    // transactions because manual entry was withdrawn would be a far worse outage than the one
    // the rollback is trying to avoid.
    ResponseEntity<String> response =
        rest.exchange("/api/insights/breakdown", HttpMethod.GET, authed(null), String.class);

    assertNotEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
    assertNotEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }
}
