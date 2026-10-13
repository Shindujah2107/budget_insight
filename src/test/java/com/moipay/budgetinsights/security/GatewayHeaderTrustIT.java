package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * What gateway headers can do when gateway authentication is switched off.
 *
 * <p>This is the consequence half of the registration defect. With
 * {@code gateway-auth-enabled=false} the filter was left out of the Spring Security chain but was
 * still registered with the servlet container, where an unordered filter bean runs innermost —
 * after Spring Security has authorized the request and immediately before the dispatcher. A
 * request that got past HTTP Basic could therefore have its {@code SecurityContext} replaced, by
 * headers, with a {@link com.moipay.budgetinsights.config.Caller} naming any user id at all, and
 * {@code @AuthenticationPrincipal} resolves after that point. Every repository query in this
 * service is scoped by that principal.
 *
 * <p>The test states the requirement rather than the history: with the flag off, gateway headers
 * decide nothing. Basic credentials are supplied here explicitly because the service no longer
 * ships any.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "moipay.budget-insights.security.gateway-auth-enabled=false",
      "moipay.budget-insights.security.gateway-internal-token=integration-test-gateway-token",
      "moipay.budget-insights.admin.username=integration-test-admin",
      "moipay.budget-insights.admin.password=integration-test-password"
    })
@ActiveProfiles("test")
@DisplayName("Gateway headers with gateway auth disabled")
class GatewayHeaderTrustIT {

  @Autowired private TestRestTemplate rest;

  private static final String SUMMARY = "/api/insights/summary";

  private static HttpHeaders gatewayHeaders(UUID userId) {
    HttpHeaders headers = new HttpHeaders();
    headers.add("X-Gateway-Token", "integration-test-gateway-token");
    headers.add("X-User-Id", userId.toString());
    headers.add("X-Tenant", UUID.randomUUID().toString());
    return headers;
  }

  @Test
  @DisplayName("Gateway headers alone do not authenticate anything")
  void headersAloneAreNotCredentials() {
    ResponseEntity<String> response =
        rest.exchange(
            SUMMARY, HttpMethod.GET, new HttpEntity<>(gatewayHeaders(UUID.randomUUID())), String.class);

    assertTrue(
        response.getStatusCode().is4xxClientError(),
        "With gateway auth off, X-Gateway-Token and X-User-Id must not authenticate a request; "
            + "got "
            + response.getStatusCode());
  }

  @Test
  @DisplayName("A Basic-authenticated caller cannot become another user by adding headers")
  void basicCallerCannotAssumeAnIdentityByHeader() {
    UUID someoneElse = UUID.randomUUID();

    ResponseEntity<String> response =
        rest.withBasicAuth("integration-test-admin", "integration-test-password")
            .exchange(
                SUMMARY,
                HttpMethod.GET,
                new HttpEntity<>(gatewayHeaders(someoneElse)),
                String.class);

    // A 2xx here would mean the headers supplied a Caller that the controller then used, which is
    // one authenticated party reading another party's data by asserting a user id.
    assertFalse(
        response.getStatusCode().is2xxSuccessful(),
        "A Basic-authenticated caller supplied X-User-Id="
            + someoneElse
            + " and received "
            + response.getStatusCode()
            + ". Gateway headers must not establish a principal when gateway auth is disabled.");
  }

  @Test
  @DisplayName("An unauthenticated request is still refused")
  void anonymousStillRefused() {
    // Guards the opposite mistake: suppressing the filter registration must not accidentally
    // open the endpoints to everyone.
    ResponseEntity<String> response = rest.getForEntity(SUMMARY, String.class);
    assertTrue(
        response.getStatusCode().is4xxClientError(),
        "An anonymous request must be refused; got " + response.getStatusCode());
  }
}
