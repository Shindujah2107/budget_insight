package com.moneyreserve.security;

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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * A path the service does not serve answers 404, not 500.
 *
 * <p>Found in DEV acceptance testing: an unmapped path raised {@code NoResourceFoundException},
 * which reached the generic handler and came back as a 500 with a stack trace at ERROR.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Unknown paths")
class UnknownPathIT {


  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("an authenticated request for an unmapped path is a 404 in the standard error shape")
  void unmappedPathIs404() {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));

    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/this-path-does-not-exist",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);

    assertEquals(404, response.getStatusCode().value(), String.valueOf(response.getBody()));
    assertTrue(String.valueOf(response.getBody()).contains("\"status\":404"), response.getBody());
  }
}
