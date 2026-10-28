package com.moneyreserve.security;

import static com.moneyreserve.security.AdminTestSupport.data;
import static com.moneyreserve.security.AdminTestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Response and error shapes the architecture doc fixes (§3.1–§3.9). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: response and error contracts")
class AdminErrorContractIT {

  @Autowired private TestRestTemplate rest;

  private AdminTestSupport admin() {
    return new AdminTestSupport(rest, UUID.randomUUID());
  }

  @Test
  @DisplayName("bean validation → 400 E.006 with details[{field, message}]")
  void beanValidationIsE006() throws Exception {
    ResponseEntity<String> r =
        admin().config(HttpMethod.POST, "/categories", "{\"code\":\"bad code\",\"active\":true}");
    assertEquals(400, r.getStatusCode().value());
    JsonNode body = json(r);
    assertEquals("MR.ADM.E.006", body.path("error").asText());
    JsonNode first = body.path("details").path(0);
    assertTrue(first.has("field") && first.has("message"), r.getBody());
  }

  @Test
  @DisplayName("an unreadable body → 400 E.006; errors without details omit the field")
  void unreadableBodyIsE006() throws Exception {
    ResponseEntity<String> r = admin().config(HttpMethod.POST, "/categories", "{not json");
    assertEquals(400, r.getStatusCode().value());
    assertEquals("MR.ADM.E.006", json(r).path("error").asText());
    ResponseEntity<String> missing =
        admin().config(HttpMethod.GET, "/categories/" + UUID.randomUUID() + "/usage", null);
    assertEquals(404, missing.getStatusCode().value());
    assertFalse(json(missing).has("details"), missing.getBody());
  }

  @Test
  @DisplayName("discard answers {revision}; releases page 50 by default; publishedBy is {staffId}")
  void shapes() throws Exception {
    AdminTestSupport admin = admin();
    admin.ensureOther();
    JsonNode discarded = data(admin.config(HttpMethod.DELETE, "/changeset", null));
    assertTrue(discarded.has("revision") && discarded.size() == 1, discarded.toString());

    admin.ensureOther();
    admin.config(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"SHAPE_" + System.nanoTime() % 100000 + "\",\"nameEn\":\"Shape\","
            + "\"iconKey\":\"ic_shopping\",\"sortOrder\":61,\"active\":true}");
    assertEquals(201, admin.publishAll().getStatusCode().value());
    JsonNode page = data(admin.config(HttpMethod.GET, "/releases", null));
    assertEquals(50, page.path("size").asInt());
    JsonNode by = page.path("items").path(0).path("publishedBy");
    assertTrue(by.isObject() && by.has("staffId") && !by.has("userId"), page.toString());
    assertEquals(200, data(admin.config(HttpMethod.GET, "/releases?size=999", null)).path("size").asInt());
  }

  @Test
  @DisplayName("list filters and paging refuse bad values with E.006")
  void badQueryValuesAreE006() throws Exception {
    for (String path : new String[] {"/categories?active=banana", "/senders?active=maybe", "/releases?page=-1"}) {
      ResponseEntity<String> r = admin().config(HttpMethod.GET, path, null);
      assertEquals(400, r.getStatusCode().value(), path + " " + r.getBody());
      assertEquals("MR.ADM.E.006", json(r).path("error").asText(), path);
    }
  }
}
