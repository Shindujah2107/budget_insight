package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Test;
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
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The Budget Insights admin API is Super Admin only.
 *
 * <p>Every admin route, discovered from the handler mappings so a new route cannot be forgotten, is
 * called anonymously and as each other role. {@code CUSTOMER} matters most: every self-registered
 * user holds it, so a rule that let it in would open the admin to everyone. The Super Admin call only has to get past the boundary; what the route then answers is
 * other tests' business.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: Super Admin only")
class AdminAuthorizationIT {

  private static final String ADMIN_PREFIX = "/api/insights/admin";
  private static final List<String> REFUSED_ROLES =
      List.of("CUSTOMER", "ADMIN");

  @Autowired private TestRestTemplate rest;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  private record Endpoint(HttpMethod method, String pattern) {

    String path() {
      return pattern.replaceAll("\\{[^}]+}", pathValue(pattern));
    }

    private static String pathValue(String pattern) {
      return pattern.contains("releases/{") ? "1" : UUID.randomUUID().toString();
    }

    @Override
    public String toString() {
      return method + " " + pattern;
    }
  }

  @Test
  @DisplayName("the admin routes exist, so the sweeps below are not vacuous")
  void adminRoutesAreDiscovered() {
    assertFalse(adminEndpoints().isEmpty(), "No " + ADMIN_PREFIX + " routes were discovered");
  }

  @TestFactory
  @DisplayName("an anonymous caller gets 401 everywhere (architecture doc §3.1)")
  Stream<DynamicTest> anonymousRefused() {
    return adminEndpoints().stream()
        .map(
            e ->
                DynamicTest.dynamicTest(
                    "anonymous " + e,
                    () -> {
                      int status = call(e, null).getStatusCode().value();
                      assertEquals(401, status, e + " answered " + status);
                    }));
  }

  @TestFactory
  @DisplayName("every role except SUPER_ADMIN gets 403 MR.ADM.E.001")
  Stream<DynamicTest> otherRolesRefused() {
    List<DynamicTest> tests = new ArrayList<>();
    for (String roles : REFUSED_ROLES) {
      for (Endpoint e : adminEndpoints()) {
        tests.add(
            DynamicTest.dynamicTest(
                roles + " " + e,
                () -> {
                  ResponseEntity<String> response = call(e, roles);
                  assertEquals(403, response.getStatusCode().value(), e + " for " + roles);
                  assertTrue(
                      response.getBody() != null && response.getBody().contains("MR.ADM.E.001"),
                      e + " did not carry MR.ADM.E.001: " + response.getBody());
                }));
      }
    }
    return tests.stream();
  }

  @TestFactory
  @DisplayName("SUPER_ADMIN gets past the boundary everywhere")
  Stream<DynamicTest> superAdminAdmitted() {
    return adminEndpoints().stream()
        .map(
            e ->
                DynamicTest.dynamicTest(
                    "SUPER_ADMIN " + e,
                    () -> {
                      int status = call(e, "SUPER_ADMIN").getStatusCode().value();
                      assertFalse(status == 401 || status == 403, e + " refused a Super Admin: " + status);
                    }));
  }

  // ---------------------------------------------------------------------------------------------

  private ResponseEntity<String> call(Endpoint e, String roles) {
    HttpHeaders headers = new HttpHeaders();
    if (roles != null) {
      headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), roles));
    }
    boolean writes =
        HttpMethod.POST.equals(e.method())
            || HttpMethod.PUT.equals(e.method())
            || HttpMethod.PATCH.equals(e.method());
    if (writes) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return rest.exchange(
        e.path(), e.method(), new HttpEntity<>(writes ? "{}" : null, headers), String.class);
  }

  private List<Endpoint> adminEndpoints() {
    List<Endpoint> found = new ArrayList<>();
    mappings
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
              for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith(ADMIN_PREFIX)) {
                  continue;
                }
                if (verbs.isEmpty()) {
                  found.add(new Endpoint(HttpMethod.GET, pattern));
                }
                for (RequestMethod verb : verbs) {
                  found.add(new Endpoint(HttpMethod.valueOf(verb.name()), pattern));
                }
              }
            });
    found.sort(Comparator.comparing(Endpoint::toString));
    return found;
  }

  private static Set<String> patternsOf(RequestMappingInfo info) {
    PathPatternsRequestCondition parsed = info.getPathPatternsCondition();
    return parsed == null ? Set.of() : parsed.getPatternValues();
  }
}
