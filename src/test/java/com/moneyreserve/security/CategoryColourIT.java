package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Categories as the app receives them: an admin's colour (V1.18.0) and names reach the app after
 * publish, an unset colour is left out, only opaque #RRGGBB is accepted, and turned-off categories
 * are listed (flagged) only when the app asks, so older transactions can still show them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Category colours from the Admin Dashboard")
class CategoryColourIT {

  @Autowired private TestRestTemplate rest;

  private AdminTestSupport admin() {
    return new AdminTestSupport(rest, UUID.randomUUID());
  }

  private static String category(String code, String colour) {
    return "{\"code\":\""
        + code
        + "\",\"nameEn\":\"Colour "
        + code
        + "\",\"iconKey\":\"ic_shopping\",\"sortOrder\":60,\"active\":true"
        + (colour == null ? "" : ",\"colourHex\":\"" + colour + "\"")
        + "}";
  }

  /** The categories a signed-in customer's app receives. */
  private JsonNode appCategories() throws Exception {
    return appCategories("");
  }

  private JsonNode appCategories(String query) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    ResponseEntity<String> r =
        rest.exchange(
            "/api/insights/categories" + query,
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return AdminTestSupport.json(r).path("items");
  }

  private static JsonNode byCode(JsonNode list, String code) {
    for (JsonNode item : list) {
      if (code.equals(item.path("code").asText())) {
        return item;
      }
    }
    throw new AssertionError(code + " not in " + list);
  }

  @Test
  @DisplayName("a published colour reaches the app; an unset one is left out")
  void publishedColourReachesTheApp() throws Exception {
    AdminTestSupport admin = admin();
    admin.ensureOther();
    assertEquals(
        201,
        admin.config(HttpMethod.POST, "/categories", category("COLOUR_SET", "#123ABC"))
            .getStatusCode()
            .value());
    assertEquals(
        201,
        admin.config(HttpMethod.POST, "/categories", category("COLOUR_UNSET", null))
            .getStatusCode()
            .value());
    ResponseEntity<String> published = admin.publishAll();
    assertTrue(published.getStatusCode().is2xxSuccessful(), published.getBody());

    JsonNode list = appCategories();
    assertEquals("#123ABC", byCode(list, "COLOUR_SET").path("colourHex").asText());
    assertTrue(byCode(list, "COLOUR_UNSET").path("colourHex").isMissingNode());
  }

  @Test
  @DisplayName("the app gets names in every language; turned-off categories only when it asks")
  void namesAndTurnedOffCategories() throws Exception {
    AdminTestSupport admin = admin();
    admin.ensureOther();
    admin.config(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"PETS_IT\",\"nameEn\":\"Pets\",\"nameSi\":\"සුරතල් සතුන්\","
            + "\"nameTa\":\"செல்லப்பிராணிகள்\",\"iconKey\":\"ic_other\",\"colourHex\":\"#5BA3A0\","
            + "\"sortOrder\":40,\"active\":true,\"spendBucket\":\"WANTS\"}");
    admin.config(
        HttpMethod.POST,
        "/categories",
        "{\"code\":\"RETIRED_IT\",\"nameEn\":\"Retired\",\"iconKey\":\"ic_other\","
            + "\"sortOrder\":41,\"active\":false}");
    assertTrue(admin.publishAll().getStatusCode().is2xxSuccessful());

    JsonNode pets = byCode(appCategories(), "PETS_IT");
    assertEquals("Pets", pets.path("name").asText());
    assertEquals("සුරතල් සතුන්", pets.path("nameSi").asText());
    assertEquals("செல்லப்பிராணிகள்", pets.path("nameTa").asText());
    assertTrue(pets.path("active").asBoolean());
    assertEquals("WANTS", pets.path("spendBucket").asText());

    for (JsonNode item : appCategories()) {
      assertTrue(!"RETIRED_IT".equals(item.path("code").asText()), "active list hides turned-off");
    }
    JsonNode retired = byCode(appCategories("?includeInactive=true"), "RETIRED_IT");
    assertTrue(!retired.path("active").asBoolean(true));
  }

  @Test
  @DisplayName("only opaque #RRGGBB colours are accepted")
  void onlyOpaqueHex() throws Exception {
    AdminTestSupport admin = admin();
    for (String bad : new String[] {"#123ABC80", "123ABC", "#12AB", "red"}) {
      ResponseEntity<String> r =
          admin.config(HttpMethod.POST, "/categories", category("COLOUR_BAD", bad));
      assertEquals(400, r.getStatusCode().value(), bad + ": " + r.getBody());
    }
  }
}
