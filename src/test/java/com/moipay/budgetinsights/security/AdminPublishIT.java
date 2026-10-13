package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Review &amp; publish and Change history (screens 23-24): a publish applies the whole changeset as
 * release N+1, empties it, and refuses a stale review or an empty changeset.
 *
 * <p>The H2 test schema has no Flyway seed, so the test drafts OTHER itself when it is missing: the
 * publish checks refuse a configuration without it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: publish and history")
class AdminPublishIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  private JsonNode data(ResponseEntity<String> response) throws Exception {
    return JSON.readTree(response.getBody()).path("data");
  }

  private ResponseEntity<String> call(HttpMethod method, String path, String body)
      throws Exception {
    return new AdminTestSupport(rest, UUID.randomUUID()).config(method, path, body);
  }

  private void ensureOther() throws Exception {
    JsonNode items = data(call(HttpMethod.GET, "/categories?q=OTHER", null)).path("items");
    for (JsonNode item : items) {
      if ("OTHER".equals(item.path("effective").path("code").asText())) {
        return;
      }
    }
    call(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"OTHER\",\"nameEn\":\"Other\",\"iconKey\":\"ic_other\",\"sortOrder\":17,"
            + "\"active\":true}");
  }

  private ResponseEntity<String> publish(long revision, int base) throws Exception {
    return call(
        HttpMethod.POST,
        "/publish",
        "{\"reason\":\"Integration test release\",\"changesetRevision\":"
            + revision
            + ",\"baseReleaseVersion\":"
            + base
            + ",\"acknowledgeWarnings\":true}");
  }

  @Test
  @DisplayName("publish creates release N+1, empties the changeset and appears in the history")
  void publishCreatesARelease() throws Exception {
    ensureOther();
    call(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"PUB_" + System.nanoTime() % 100000 + "\",\"nameEn\":\"Published\","
            + "\"iconKey\":\"ic_shopping\",\"sortOrder\":60,\"active\":true}");
    JsonNode changeset = data(call(HttpMethod.GET, "/changeset", null));
    assertTrue(changeset.path("changes").size() > 0);

    ResponseEntity<String> published =
        publish(changeset.path("revision").asLong(), changeset.path("baseReleaseVersion").asInt());
    assertEquals(201, published.getStatusCode().value(), published.getBody());
    int version = data(published).path("version").asInt();
    assertEquals(changeset.path("baseReleaseVersion").asInt() + 1, version);

    assertEquals(0, data(call(HttpMethod.GET, "/changeset", null)).path("changes").size());
    ResponseEntity<String> release = call(HttpMethod.GET, "/releases/" + version, null);
    assertEquals(200, release.getStatusCode().value());
    assertTrue(data(release).path("diff").size() > 0, release.getBody());
  }

  @Test
  @DisplayName("a stale revision is refused with MOI.BI.ADM.E.010")
  void staleReviewRefused() throws Exception {
    ensureOther();
    JsonNode changeset = data(call(HttpMethod.GET, "/changeset", null));
    ResponseEntity<String> stale =
        publish(
            changeset.path("revision").asLong() - 1, changeset.path("baseReleaseVersion").asInt());
    assertEquals(409, stale.getStatusCode().value());
    assertTrue(stale.getBody().contains("MOI.BI.ADM.E.010"), stale.getBody());
  }

  @Test
  @DisplayName("an empty changeset is refused with MOI.BI.ADM.E.017")
  void emptyChangesetRefused() throws Exception {
    ensureOther();
    JsonNode first = data(call(HttpMethod.GET, "/changeset", null));
    if (first.path("changes").size() > 0) {
      publish(first.path("revision").asLong(), first.path("baseReleaseVersion").asInt());
    }
    JsonNode empty = data(call(HttpMethod.GET, "/changeset", null));
    ResponseEntity<String> response =
        publish(empty.path("revision").asLong(), empty.path("baseReleaseVersion").asInt());
    assertEquals(409, response.getStatusCode().value());
    assertTrue(response.getBody().contains("MOI.BI.ADM.E.017"), response.getBody());
  }

  @Test
  @DisplayName("the checks list the checklist the Review screen shows")
  void validateListsChecks() throws Exception {
    JsonNode validation = data(call(HttpMethod.POST, "/changeset/validate", null));
    assertTrue(validation.path("checks").size() >= 5, validation.toString());
  }
}
