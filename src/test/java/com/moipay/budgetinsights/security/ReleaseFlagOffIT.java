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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Each release flag withdraws its own release and nothing else.
 *
 * <p>The claim a release flag makes is not "this endpoint stops working" — it is "this endpoint
 * behaves exactly as it did before the release existed, and every other release is untouched". The
 * second half is the one that goes wrong quietly: a gate wired to the wrong property, or a shared
 * guard, withdraws more than it was asked to, and the first anyone knows is a customer losing a
 * screen nobody meant to take away.
 *
 * <p>All four release flags here are off at once, deliberately. Testing them one at a time would
 * not catch a gate that reads its neighbour's flag.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "moipay.budget-insights.budget.enabled=false",
      "moipay.budget-insights.activity.enabled=false",
      "moipay.budget-insights.alerts.enabled=false",
      "moipay.budget-insights.recommendations.enabled=false"
    })
@ActiveProfiles("test")
@DisplayName("Release flags off")
class ReleaseFlagOffIT {

  @Autowired private TestRestTemplate rest;

  private static final String TOKEN = "integration-test-gateway-token";

  private HttpEntity<Void> caller() {
    HttpHeaders h = new HttpHeaders();
    h.add("X-Gateway-Token", TOKEN);
    h.add("X-User-Id", UUID.randomUUID().toString());
    h.add("X-Tenant", UUID.randomUUID().toString());
    return new HttpEntity<>(h);
  }

  private void assertNotImplemented(String path) {
    ResponseEntity<String> response = rest.exchange(path, HttpMethod.GET, caller(), String.class);
    assertEquals(
        HttpStatus.NOT_IMPLEMENTED,
        response.getStatusCode(),
        path + " should answer 501 with its release flag off");
  }

  @Test
  @DisplayName("R2 summary answers 501")
  void summaryWithdrawn() {
    assertNotImplemented("/api/insights/summary");
  }

  @Test
  @DisplayName("R5 activity answers 501")
  void activityWithdrawn() {
    assertNotImplemented("/api/insights/activity");
  }

  @Test
  @DisplayName("R6 alerts answers 501")
  void alertsWithdrawn() {
    assertNotImplemented("/api/insights/alerts");
  }

  @Test
  @DisplayName("R6 recommendations answers 501")
  void recommendationsWithdrawn() {
    assertNotImplemented("/api/insights/recommendations");
  }

  @Test
  @DisplayName("R1 breakdown is untouched by any of them")
  void breakdownSurvives() {
    // R1 has no flag of its own for reads. If a gate were reading the master switch, or sharing a
    // guard with its neighbours, this is where it would show.
    ResponseEntity<String> response =
        rest.exchange("/api/insights/breakdown", HttpMethod.GET, caller(), String.class);

    assertEquals(
        HttpStatus.OK,
        response.getStatusCode(),
        "Withdrawing R2, R5 and R6 must not take the R1 breakdown with them");
  }

}
