package com.moneyreserve.security;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Category drafts (screens 02-04): a new category is a draft until publish, codes are unique across
 * drafts, the changeset revision guards concurrent edits, and deleting a never-published draft
 * simply drops it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: category drafts")
class AdminCategoryDraftsIT {

  private static final String CATEGORIES = "/api/insights/admin/config/categories";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  private static HttpEntity<String> superAdmin(String body, String ifMatch) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), "SUPER_ADMIN"));
    if (ifMatch != null) {
      headers.add("If-Match", ifMatch);
    }
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return new HttpEntity<>(body, headers);
  }

  private static String category(String code) {
    return "{\"code\":\""
        + code
        + "\",\"nameEn\":\"Test "
        + code
        + "\",\"iconKey\":\"ic_shopping\",\"sortOrder\":50,\"active\":true}";
  }

  /** The current changeset revision, as the dashboard reads it before a write. */
  private String currentRevision() throws Exception {
    ResponseEntity<String> cs =
        rest.exchange(
            "/api/insights/admin/config/changeset",
            HttpMethod.GET,
            superAdmin(null, null),
            String.class);
    return "\"" + JSON.readTree(cs.getBody()).path("data").path("revision").asLong() + "\"";
  }

  /** POST with the current revision in If-Match, or with {@code ifMatch} when given. */
  private ResponseEntity<String> post(String code, String ifMatch) throws Exception {
    return rest.exchange(
        CATEGORIES,
        HttpMethod.POST,
        superAdmin(category(code), ifMatch != null ? ifMatch : currentRevision()),
        String.class);
  }

  @Test
  @DisplayName("a new category is a DRAFT_NEW item and bumps the revision")
  void createIsADraft() throws Exception {
    ResponseEntity<String> created = post("DRAFT_ONE", null);
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    JsonNode data = JSON.readTree(created.getBody()).path("data");
    assertEquals("DRAFT_NEW", data.path("item").path("state").asText());
    assertTrue(data.path("changesetRevision").asLong() > 0);

    ResponseEntity<String> list =
        rest.exchange(CATEGORIES + "?q=DRAFT_ONE", HttpMethod.GET, superAdmin(null, null), String.class);
    assertTrue(list.getBody().contains("DRAFT_ONE"), list.getBody());
  }

  @Test
  @DisplayName("a second category with the same code is refused with MR.ADM.E.008")
  void duplicateCodeRefused() throws Exception {
    post("DRAFT_DUP", null);
    ResponseEntity<String> again = post("DRAFT_DUP", null);
    assertEquals(409, again.getStatusCode().value());
    assertTrue(again.getBody().contains("MR.ADM.E.008"), again.getBody());
  }

  @Test
  @DisplayName("a stale If-Match is refused with MR.ADM.E.010")
  void staleRevisionRefused() throws Exception {
    post("DRAFT_REV", null);
    ResponseEntity<String> stale = post("DRAFT_REV_TWO", "\"0\"");
    assertEquals(409, stale.getStatusCode().value());
    assertTrue(stale.getBody().contains("MR.ADM.E.010"), stale.getBody());
  }

  @Test
  @DisplayName("deleting a never-published draft drops it")
  void deletingANewDraftDropsIt() throws Exception {
    ResponseEntity<String> created = post("DRAFT_GONE", null);
    String id = JSON.readTree(created.getBody()).path("data").path("item").path("id").asText();

    ResponseEntity<String> deleted =
        rest.exchange(
            CATEGORIES + "/" + id,
            HttpMethod.DELETE,
            superAdmin(null, currentRevision()),
            String.class);
    assertEquals(200, deleted.getStatusCode().value(), deleted.getBody());
    assertTrue(JSON.readTree(deleted.getBody()).path("data").path("item").isNull());

    ResponseEntity<String> list =
        rest.exchange(CATEGORIES + "?q=DRAFT_GONE", HttpMethod.GET, superAdmin(null, null), String.class);
    assertTrue(!list.getBody().contains("DRAFT_GONE"), list.getBody());
  }

  @Test
  @DisplayName("an icon the app does not ship is refused")
  void unknownIconRefused() throws Exception {
    ResponseEntity<String> response =
        rest.exchange(
            CATEGORIES,
            HttpMethod.POST,
            superAdmin(
                "{\"code\":\"DRAFT_ICON\",\"nameEn\":\"Icon\",\"iconKey\":\"ic_rocket\","
                    + "\"sortOrder\":1,\"active\":true}",
                currentRevision()),
            String.class);
    assertEquals(400, response.getStatusCode().value());
    assertTrue(response.getBody().contains("MR.ADM.E.006"), response.getBody());
  }

  @Test
  @DisplayName("a write without If-Match is refused with MR.ADM.E.010")
  void missingIfMatchRefused() {
    for (String ifMatch : new String[] {null, "*"}) {
      ResponseEntity<String> response =
          rest.exchange(
              CATEGORIES,
              HttpMethod.POST,
              superAdmin(category("DRAFT_NO_IF_MATCH"), ifMatch),
              String.class);
      assertEquals(409, response.getStatusCode().value(), response.getBody());
      assertTrue(response.getBody().contains("MR.ADM.E.010"), response.getBody());
    }
  }
}
