package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The Postman collection covers every route the service publishes (BI-31).
 *
 * <p>A collection is documentation, and documentation rots the moment it is maintained by hand. The
 * route list here is read from Spring's handler mapping, exactly as {@link
 * EndpointAuthorizationSweepIT} reads it, so a route added next month and not added to the
 * collection fails the build rather than quietly going undocumented until somebody integrating
 * against it asks why it is missing.
 *
 * <p>It checks coverage, not correctness: that every published route appears, and that the
 * collection does not advertise a route that no longer exists. What the example bodies contain is
 * beyond what a test can usefully assert.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Postman collection coverage")
class PostmanCollectionCoverageIT {

  private static final String COLLECTION =
      "docs/postman/MoneyReserve-API.postman_collection.json";
  private static final String PREFIX = "/api/insights";

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  private RequestMappingHandlerMapping mappings;

  @Test
  @DisplayName("every published route has a request, and no request names a route that is gone")
  void theCollectionAndTheServiceAgree() throws IOException {
    Set<String> published = publishedRoutes();
    Set<String> documented = documentedRoutes();

    assertFalse(published.isEmpty(), "No routes were discovered, so this would pass vacuously");
    assertFalse(documented.isEmpty(), "The Postman collection is empty or was not found");

    Set<String> undocumented = new TreeSet<>(published);
    undocumented.removeAll(documented);
    assertTrue(
        undocumented.isEmpty(),
        "These routes are published but missing from " + COLLECTION + ": " + undocumented);

    Set<String> stale = new TreeSet<>(documented);
    stale.removeAll(published);
    assertTrue(
        stale.isEmpty(),
        "The Postman collection documents routes this service no longer serves: " + stale);
  }

  // ---------------------------------------------------------------------------------

  /** "GET /api/insights/breakdown", with path variables normalised to {}. */
  private Set<String> publishedRoutes() {
    Set<String> out = new LinkedHashSet<>();
    mappings
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
              for (String pattern : patternsOf(info)) {
                if (!pattern.startsWith(PREFIX)) {
                  continue;
                }
                String path = normalise(pattern);
                if (verbs.isEmpty()) {
                  out.add("GET " + path);
                  continue;
                }
                for (RequestMethod verb : verbs) {
                  out.add(verb.name() + " " + path);
                }
              }
            });
    return out;
  }

  private Set<String> documentedRoutes() throws IOException {
    File file = new File(COLLECTION);
    assertTrue(
        file.isFile(),
        "The Postman collection is missing. Expected it at " + file.getAbsolutePath());

    JsonNode root = new ObjectMapper().readTree(file);
    Set<String> out = new LinkedHashSet<>();
    for (JsonNode request : requests(root.path("item"))) {
      String method = request.path("request").path("method").asText();
      String raw = request.path("request").path("url").path("raw").asText();
      int query = raw.indexOf('?');
      String path = (query < 0 ? raw : raw.substring(0, query)).replace("{{baseUrl}}", "");
      if (path.startsWith(PREFIX)) {
        out.add(method + " " + normalise(path));
      }
    }
    return out;
  }

  /** Folders may nest; a node with a "request" is a leaf. */
  private List<JsonNode> requests(JsonNode items) {
    List<JsonNode> out = new ArrayList<>();
    for (JsonNode node : items) {
      if (node.has("request")) {
        out.add(node);
      } else if (node.has("item")) {
        out.addAll(requests(node.get("item")));
      }
    }
    return out;
  }

  /**
   * Both sides name their variables differently — Spring writes {@code {id}} and Postman writes
   * {@code {{transactionId}}} — and neither name is part of the route. Collapsing both to {@code
   * {}} compares what is actually being compared: the shape of the path.
   */
  private String normalise(String path) {
    return path.replaceAll("\\{\\{[^}]+}}", "{}").replaceAll("\\{[^{}]+}", "{}");
  }

  private static Set<String> patternsOf(RequestMappingInfo info) {
    PathPatternsRequestCondition parsed = info.getPathPatternsCondition();
    if (parsed != null) {
      return parsed.getPatternValues();
    }
    PatternsRequestCondition ant = info.getPatternsCondition();
    return ant == null ? Set.of() : ant.getPatterns();
  }
}
