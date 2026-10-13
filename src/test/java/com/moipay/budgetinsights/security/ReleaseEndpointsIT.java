package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Every R1–R6 read route answers over HTTP, with the flags on.
 *
 * <p>The service's unit tests assert what each service class computes. Nothing until now asserted
 * that the routes are mapped, reachable, authenticated and returning 200 — so a controller with a
 * wrong path, a missing bean, or a gate reading the wrong flag would have shipped with a green
 * build. This is the smoke test the release flags are supposed to control.
 *
 * <p>Ingest is absent by design: it is the one release whose flag is off in DEV pending Compliance
 * gate 2, and {@code ReleaseFlagOffIT} covers what "off" looks like.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("R1–R6 routes with their flags on")
class ReleaseEndpointsIT {

  @Autowired private TestRestTemplate rest;

  private static final String TOKEN = "integration-test-gateway-token";

  private HttpEntity<Void> caller() {
    HttpHeaders h = new HttpHeaders();
    h.add("X-Gateway-Token", TOKEN);
    h.add("X-User-Id", UUID.randomUUID().toString());
    h.add("X-Tenant", UUID.randomUUID().toString());
    return new HttpEntity<>(h);
  }

  private void assertReachable(String release, String path) {
    ResponseEntity<String> response = rest.exchange(path, HttpMethod.GET, caller(), String.class);
    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        release + " " + path + " should answer 2xx with its flag on; got "
            + response.getStatusCode()
            + " "
            + response.getBody());
  }

  @Test
  @DisplayName("R1 breakdown")
  void breakdown() {
    assertReachable("R1", "/api/insights/breakdown");
  }

  @Test
  @DisplayName("R2 summary")
  void summary() {
    assertReachable("R2", "/api/insights/summary");
  }

  @Test
  @DisplayName("R2 budget")
  void budget() {
    assertReachable("R2", "/api/insights/budget");
  }

  @Test
  @DisplayName("R5 activity")
  void activity() {
    assertReachable("R5", "/api/insights/activity");
  }

  @Test
  @DisplayName("R6 alerts")
  void alerts() {
    assertReachable("R6", "/api/insights/alerts");
  }

  @Test
  @DisplayName("R6 recommendations")
  void recommendations() {
    assertReachable("R6", "/api/insights/recommendations");
  }

  @Test
  @DisplayName("A caller with no history still gets an answer, not an error")
  void emptyHistoryIsNotAnError() {
    // Every caller in this class is a fresh random user id, so all of the above already run
    // against an empty account. Stated explicitly because "works for a customer with data" and
    // "works on the day someone signs up" are different claims, and the second is the one a new
    // customer experiences.
    ResponseEntity<String> response =
        rest.exchange("/api/insights/recommendations", HttpMethod.GET, caller(), String.class);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(
        response.getBody() != null && response.getBody().contains("\"success\":true"),
        "Expected the standard envelope, got " + response.getBody());
  }
}
