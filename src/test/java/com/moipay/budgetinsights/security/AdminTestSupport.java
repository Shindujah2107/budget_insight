package com.moipay.budgetinsights.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Calls to the admin API as a Super Admin, shared by the admin integration tests. */
final class AdminTestSupport {

  static final String CONFIG = "/api/insights/admin/config";
  static final ObjectMapper JSON = new ObjectMapper();

  private final TestRestTemplate rest;
  private final UUID staff;

  AdminTestSupport(TestRestTemplate rest, UUID staff) {
    this.rest = rest;
    this.staff = staff;
  }

  ResponseEntity<String> call(HttpMethod method, String path, String body) {
    return call(method, path, body, null);
  }

  ResponseEntity<String> call(HttpMethod method, String path, String body, String ifMatch) {
    HttpHeaders headers = new HttpHeaders();
    if (ifMatch != null) {
      headers.add("If-Match", ifMatch);
    }
    headers.add("X-Gateway-Token", "integration-test-gateway-token");
    headers.add("X-User-Id", staff.toString());
    headers.add("X-User-Roles", "SUPER_ADMIN");
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  /**
   * A configuration call. Writes carry {@code If-Match} with the current changeset revision, as
   * the dashboard sends it; publish and validate take none.
   */
  ResponseEntity<String> config(HttpMethod method, String path, String body) throws Exception {
    boolean write =
        !HttpMethod.GET.equals(method)
            && !path.startsWith("/publish")
            && !path.startsWith("/changeset/validate");
    if (!write) {
      return call(method, CONFIG + path, body);
    }
    long revision = data(call(HttpMethod.GET, CONFIG + "/changeset", null)).path("revision").asLong();
    return call(method, CONFIG + path, body, "\"" + revision + "\"");
  }

  static JsonNode json(ResponseEntity<String> response) throws Exception {
    return JSON.readTree(response.getBody());
  }

  static JsonNode data(ResponseEntity<String> response) throws Exception {
    return json(response).path("data");
  }

  /** Drafts OTHER when the H2 schema (no Flyway seed) lacks it: publish refuses without it. */
  void ensureOther() throws Exception {
    for (JsonNode item : data(config(HttpMethod.GET, "/categories?q=OTHER", null)).path("items")) {
      if ("OTHER".equals(item.path("effective").path("code").asText())) {
        return;
      }
    }
    config(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"OTHER\",\"nameEn\":\"Other\",\"iconKey\":\"ic_other\",\"sortOrder\":17,"
            + "\"active\":true}");
  }

  /** Publishes whatever is pending, acknowledging warnings; returns the response. */
  ResponseEntity<String> publishAll() throws Exception {
    JsonNode cs = data(config(HttpMethod.GET, "/changeset", null));
    return config(
        HttpMethod.POST,
        "/publish",
        "{\"reason\":\"Integration test release\",\"changesetRevision\":"
            + cs.path("revision").asLong()
            + ",\"baseReleaseVersion\":"
            + cs.path("baseReleaseVersion").asInt()
            + ",\"acknowledgeWarnings\":true}");
  }
}
