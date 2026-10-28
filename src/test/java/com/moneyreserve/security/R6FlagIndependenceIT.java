package com.moneyreserve.security;

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
 * The R6 two-flag deviation, asserted rather than argued about.
 *
 * <p>The DIP gives R6 one flag. The implementation ships two, {@code alerts.enabled} and
 * {@code recommendations.enabled}, on the grounds that the two features fail and roll back
 * independently. That justification is only worth anything if the flags really are independent,
 * and the only configuration that demonstrates it is this one: alerts off, recommendations on.
 *
 * <p>Neither the all-on nor the all-off test can show this. Both would pass against a single flag
 * wired to two properties.
 *
 * <p>If the deviation is rejected on review, this class is the first thing to delete.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "moneyreserve.insights.alerts.enabled=false",
      "moneyreserve.insights.recommendations.enabled=true"
    })
@ActiveProfiles("test")
@DisplayName("R6 — alerts and recommendations roll back separately")
class R6FlagIndependenceIT {

  @Autowired private TestRestTemplate rest;

  private HttpEntity<Void> caller() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    return new HttpEntity<>(h);
  }

  @Test
  @DisplayName("Alerts withdrawn answers 501")
  void alertsWithdrawn() {
    ResponseEntity<String> response =
        rest.exchange("/api/insights/alerts", HttpMethod.GET, caller(), String.class);
    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }

  @Test
  @DisplayName("Recommendations keeps answering while alerts is withdrawn")
  void recommendationsSurvives() {
    // The point of the second flag. Pulling a defective alert evaluation must not silence the
    // recommendations panel, and pulling the panel must not stop a customer being told they have
    // gone past their budget.
    ResponseEntity<String> response =
        rest.exchange("/api/insights/recommendations", HttpMethod.GET, caller(), String.class);
    assertEquals(
        HttpStatus.OK,
        response.getStatusCode(),
        "Recommendations must not be withdrawn by the alerts flag; got "
            + response.getStatusCode());
  }
}
