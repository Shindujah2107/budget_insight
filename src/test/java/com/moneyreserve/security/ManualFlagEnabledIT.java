package com.moneyreserve.security;

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
 * The R1 manual-entry release gate, switched on.
 *
 * <p>Without this, {@code ManualFlagDisabledIT} would pass just as happily against a gate wired
 * shut — a flag that returns 501 in both positions is not a flag. The assertions are deliberately
 * "not 501" rather than "200": the point is that the request reaches the logic behind the gate,
 * and what that logic then says about an incomplete body is validation's business, tested
 * elsewhere.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moneyreserve.insights.manual.enabled=true")
@ActiveProfiles("test")
@DisplayName("Manual entry gate — enabled")
class ManualFlagEnabledIT {

  @Autowired private TestRestTemplate rest;

  private static final String TRANSACTIONS = "/api/insights/transactions";

  private static HttpEntity<String> authed(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    return new HttpEntity<>(body, headers);
  }

  @Test
  @DisplayName("Creating by hand is no longer refused by the gate")
  void createReachesTheService() {
    ResponseEntity<String> response =
        rest.exchange(TRANSACTIONS, HttpMethod.POST, authed("{}"), String.class);

    assertNotEquals(
        HttpStatus.NOT_IMPLEMENTED,
        response.getStatusCode(),
        "With the flag on the gate must be open; got " + response.getStatusCode());
  }

  @Test
  @DisplayName("Editing by hand is no longer refused by the gate")
  void updateReachesTheService() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + UUID.randomUUID(), HttpMethod.PATCH, authed("{}"), String.class);

    assertNotEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }

  @Test
  @DisplayName("Deleting by hand is no longer refused by the gate")
  void deleteReachesTheService() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + UUID.randomUUID(), HttpMethod.DELETE, authed(null), String.class);

    assertNotEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }
}
