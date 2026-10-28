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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The master switch outranks every release gate.
 *
 * <p>404 and 501 are different promises. 404 says Budget Insights is not part of this build, and
 * the client hides the tab. 501 says the tab is here and one capability inside it is withdrawn.
 * Getting that precedence backwards would show a customer an Insights tab whose buttons all report
 * "not available" — visibly broken rather than absent, which is the outcome the master switch
 * exists to avoid.
 *
 * <p>This is the interaction {@link com.moneyreserve.config.ManualEntryGate} is most
 * likely to break, because it runs before the controller that decides the 404: an interceptor that
 * answered 501 without first checking the master switch would pass every test in
 * {@code ManualFlagDisabledIT} and still be wrong.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "moneyreserve.insights.enabled=false",
      "moneyreserve.insights.manual.enabled=false"
    })
@ActiveProfiles("test")
@DisplayName("Master switch off")
class MasterSwitchIT {

  @Autowired private TestRestTemplate rest;

  private static final String TRANSACTIONS = "/api/insights/transactions";

  private static HttpEntity<String> authed(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    return new HttpEntity<>(body, headers);
  }

  @Test
  @DisplayName("A manual write answers 404, not the release gate's 501")
  void masterSwitchOutranksTheManualGate() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS,
            HttpMethod.POST,
            authed("{\"amount\":100.00,\"direction\":\"DEBIT\"}"),
            String.class);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  @DisplayName("Reads answer 404 too — the whole feature is absent")
  void readsAlsoAbsent() {
    ResponseEntity<String> response =
        rest.exchange("/api/insights/breakdown", HttpMethod.GET, authed(null), String.class);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }

  @Test
  @DisplayName("The admin API is absent too, even for a Super Admin")
  void adminAbsent() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), "SUPER_ADMIN"));
    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/admin/overview",
            HttpMethod.GET,
            new HttpEntity<>(null, headers),
            String.class);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }
}
