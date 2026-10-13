package com.moipay.budgetinsights.security;

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
 * What it takes to be let in, with gateway authentication on as it is in every AWS environment.
 *
 * <p>This service has no scopes. Core guarded these endpoints with {@code SCOPE_INSIGHTS_*}
 * resolved against its {@code rbac} schema; this service has no such schema and no way to resolve
 * a scope, so the entire access control story is two things: the gateway token proves the request
 * came through the gateway, and {@code X-User-Id} says who it is for. Every repository query is
 * then scoped by that id. Both halves are load-bearing, and this fixes what each one rejects.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Authorisation boundary")
class AuthorizationBoundaryIT {

  @Autowired private TestRestTemplate rest;

  private static final String SUMMARY = "/api/insights/summary";
  private static final String TOKEN = "integration-test-gateway-token";

  private HttpEntity<Void> headers(String token, String userId) {
    HttpHeaders h = new HttpHeaders();
    if (token != null) {
      h.add("X-Gateway-Token", token);
    }
    if (userId != null) {
      h.add("X-User-Id", userId);
    }
    h.add("X-Tenant", UUID.randomUUID().toString());
    return new HttpEntity<>(h);
  }

  private void assertRefused(String label, HttpEntity<Void> entity) {
    ResponseEntity<String> response =
        rest.exchange(SUMMARY, HttpMethod.GET, entity, String.class);
    assertTrue(
        response.getStatusCode().is4xxClientError(),
        label + " must be refused; got " + response.getStatusCode());
  }

  @Test
  @DisplayName("No headers at all is refused")
  void anonymous() {
    assertRefused("An anonymous request", headers(null, null));
  }

  @Test
  @DisplayName("A user id without the gateway token is refused")
  void userIdWithoutToken() {
    // Otherwise anything that could reach the container directly, bypassing the gateway, could
    // name any customer it liked.
    assertRefused("X-User-Id with no gateway token", headers(null, UUID.randomUUID().toString()));
  }

  @Test
  @DisplayName("A wrong gateway token is refused")
  void wrongToken() {
    assertRefused("A mismatched token", headers("not-the-token", UUID.randomUUID().toString()));
  }

  @Test
  @DisplayName("The right token with no user id is refused")
  void tokenWithoutUserId() {
    // The token proves where the request came from, not who it is for. Being inside the perimeter
    // is not the same as being a customer.
    assertRefused("A valid token with no X-User-Id", headers(TOKEN, null));
  }

  @Test
  @DisplayName("A user id that is not a UUID is refused rather than coerced")
  void malformedUserId() {
    // GatewayAuthFilter leaves a non-UUID unauthenticated rather than admitting a principal
    // nothing can use. The alternative is a caller with an id no query can match, which reads as
    // an empty account rather than as the refusal it actually is.
    assertRefused("A non-UUID X-User-Id", headers(TOKEN, "not-a-uuid"));
  }

  @Test
  @DisplayName("The right token with a valid user id is admitted")
  void admitted() {
    // Without this the five refusals above would all pass against a service that refuses
    // everything, which is not the property under test.
    ResponseEntity<String> response =
        rest.exchange(
            SUMMARY, HttpMethod.GET, headers(TOKEN, UUID.randomUUID().toString()), String.class);

    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "A properly identified caller must be admitted; got " + response.getStatusCode());
  }
}
