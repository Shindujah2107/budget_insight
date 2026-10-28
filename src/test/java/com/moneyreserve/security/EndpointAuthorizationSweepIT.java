package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route the service actually publishes, checked against the authorisation boundary.
 *
 * <p>The endpoint list is not written down here. It is read from Spring's handler mapping at
 * runtime, which is the only list that cannot go stale: a route added to a controller next month
 * appears in this test the moment it is mapped, whether or not anyone remembered to extend a test.
 * A hand-maintained list would pass forever while the one endpoint nobody added to it served an
 * anonymous caller.
 *
 * <p>Two sweeps, and the second is what gives the first its meaning. Without a check that an
 * identified caller gets past the boundary, a service that refused every request equally would
 * score full marks.
 *
 * <p>The refusal asserted is <b>401 or 403 specifically</b>, not "any 4xx". A route that answered
 * 404 to an anonymous caller would satisfy a loose 4xx assertion while telling us nothing about
 * whether it is protected.
 *
 * <p>That precision is also why the second sweep gives each caller consent first.
 * {@code GET /api/insights/reference/sync} sits behind the consent gate and answers <b>403</b> to a
 * customer who has not granted it — a refusal about consent, not about identity, and
 * indistinguishable from the other at the status line. Granting consent removes the ambiguity
 * rather than papering over it: the alternative, accepting 403 from an identified caller, would
 * have blinded this sweep to half of what it exists to catch.
 *
 * <p>A fresh caller per request, rather than one for the whole sweep, because the sweep itself
 * calls {@code DELETE /api/insights/consent}. Sharing a caller would make the result depend on the
 * order the routes happen to be visited in.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Authorisation sweep across every mapped endpoint")
class EndpointAuthorizationSweepIT {

  private static final String PREFIX = "/api/insights";
  // Admin routes admit Super Admins only; AdminAuthorizationIT sweeps them role by role.
  private static final String ADMIN_PREFIX = "/api/insights/admin";
  private static final String CONSENT = PREFIX + "/consent";
  private static final String GRANT =
      "{\"consentVersion\":\"1.0\",\"policyHash\":\"sweep\",\"channel\":\"ANDROID\"}";

  @Autowired private TestRestTemplate rest;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  /** One mapped route: a verb and the pattern as the controller declared it. */
  private record Endpoint(HttpMethod method, String pattern) {

    /** A concrete path, with any {@code {id}} filled by a UUID that belongs to nobody. */
    String path() {
      return pattern.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
      return method + " " + pattern;
    }
  }

  // ---------------------------------------------------------------------------------
  // The two sweeps
  // ---------------------------------------------------------------------------------

  @TestFactory
  @DisplayName("no mapped endpoint admits an anonymous caller")
  Stream<DynamicTest> noEndpointAdmitsAnAnonymousCaller() {
    List<Endpoint> endpoints = insightsEndpoints();
    assertFalse(
        endpoints.isEmpty(),
        "No /api/insights routes were discovered, so this sweep would pass vacuously");

    return endpoints.stream()
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    "anonymous " + endpoint,
                    () -> {
                      ResponseEntity<String> response =
                          rest.exchange(
                              endpoint.path(),
                              endpoint.method(),
                              request(endpoint.method(), null),
                              String.class);
                      int status = response.getStatusCode().value();
                      assertTrue(
                          status == 401 || status == 403,
                          endpoint + " answered " + status + " to an anonymous caller");
                    }));
  }

  @TestFactory
  @DisplayName("every mapped endpoint admits an identified caller")
  Stream<DynamicTest> everyEndpointAdmitsAnIdentifiedCaller() {
    // The control for the sweep above. What happens after admission -- 400 for an empty body, 404
    // for an id that is not theirs, 501 for a release not yet fulfilled -- is another test's
    // business. All that matters here is that the boundary let them in.
    return insightsEndpoints().stream()
        .filter(endpoint -> !endpoint.pattern().startsWith(ADMIN_PREFIX))
        .map(
            endpoint ->
                DynamicTest.dynamicTest(
                    "identified " + endpoint,
                    () -> {
                      UUID caller = callerWithConsent();
                      ResponseEntity<String> response =
                          rest.exchange(
                              endpoint.path(),
                              endpoint.method(),
                              request(endpoint.method(), caller),
                              String.class);
                      int status = response.getStatusCode().value();
                      assertFalse(
                          status == 401 || status == 403,
                          endpoint
                              + " refused a properly identified caller with "
                              + status
                              + ": "
                              + response.getBody());
                    }));
  }

  // ---------------------------------------------------------------------------------
  // Discovery
  // ---------------------------------------------------------------------------------

  private List<Endpoint> insightsEndpoints() {
    List<Endpoint> found = new ArrayList<>();
    mappings
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
              for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith(PREFIX)) {
                  continue;
                }
                if (verbs.isEmpty()) {
                  // A mapping with no verb answers all of them; GET is enough to prove the point.
                  found.add(new Endpoint(HttpMethod.GET, pattern));
                  continue;
                }
                for (RequestMethod verb : verbs) {
                  found.add(new Endpoint(HttpMethod.valueOf(verb.name()), pattern));
                }
              }
            });
    found.sort(Comparator.comparing(Endpoint::toString));
    return found;
  }

  /**
   * The declared patterns, whichever matching strategy is in force.
   *
   * <p>Spring Boot parses patterns by default, so the first branch is the live one. The second
   * exists because the matching strategy is a property a future change could flip, and a sweep that
   * silently found nothing would report a clean pass over zero endpoints.
   */
  private static Set<String> patternsOf(RequestMappingInfo info) {
    PathPatternsRequestCondition parsed = info.getPathPatternsCondition();
    if (parsed != null) {
      return parsed.getPatternValues();
    }
    PatternsRequestCondition ant = info.getPatternsCondition();
    return ant == null ? Set.of() : ant.getPatterns();
  }

  // ---------------------------------------------------------------------------------
  // Callers
  // ---------------------------------------------------------------------------------

  private UUID callerWithConsent() {
    UUID caller = UUID.randomUUID();
    ResponseEntity<String> granted =
        rest.exchange(
            CONSENT,
            HttpMethod.POST,
            new HttpEntity<>(GRANT, headers(caller, true)),
            String.class);
    assertTrue(
        granted.getStatusCode().is2xxSuccessful(),
        "Setup failed granting consent for the sweep: "
            + granted.getStatusCode()
            + " "
            + granted.getBody());
    return caller;
  }

  private HttpHeaders headers(UUID caller, boolean json) {
    HttpHeaders headers = new HttpHeaders();
    if (caller != null) {
      headers.setBearerAuth(TestTokens.bearer(caller));
    }
    if (json) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return headers;
  }

  private HttpEntity<String> request(HttpMethod method, UUID caller) {
    boolean writes =
        HttpMethod.POST.equals(method)
            || HttpMethod.PUT.equals(method)
            || HttpMethod.PATCH.equals(method);
    // An empty object, not a valid payload: the boundary must refuse before anything binds it.
    return new HttpEntity<>(writes ? "{}" : null, headers(caller, writes));
  }
}
