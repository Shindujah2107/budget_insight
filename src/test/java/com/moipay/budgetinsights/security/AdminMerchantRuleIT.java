package com.moipay.budgetinsights.security;

import static com.moipay.budgetinsights.security.AdminTestSupport.data;
import static com.moipay.budgetinsights.security.AdminTestSupport.json;
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

/** Merchant Rules (M1–M6, screens 05–10): form in, generated rule stored as a draft. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: merchant rules")
class AdminMerchantRuleIT {

  @Autowired private TestRestTemplate rest;

  private String category(AdminTestSupport admin) throws Exception {
    admin.ensureOther();
    ResponseEntity<String> r =
        admin.config(
            HttpMethod.POST,
            "/categories",
            "{\"code\":\"RULES_" + System.nanoTime() % 100000 + "\",\"nameEn\":\"Rules\",\"iconKey\":\"ic_shopping\","
                + "\"sortOrder\":40,\"active\":true}");
    return data(r).path("item").path("id").asText();
  }

  @Test
  @DisplayName("a SIMPLE rule is stored as the generated pattern and read back as names; lists carry no pattern")
  void simpleRule() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String cat = category(admin);
    ResponseEntity<String> created =
        admin.config(
            HttpMethod.POST,
            "/merchant-rules",
            "{\"mode\":\"SIMPLE\",\"matchType\":\"PREFIX\",\"names\":[\"uber\",\"pickme\"],\"categoryId\":\"" + cat
                + "\",\"priorityLevel\":\"HIGH\",\"active\":true}");
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    JsonNode item = data(created).path("item");
    assertEquals("PREFIX", item.path("matchType").asText());
    assertEquals("[\"UBER\",\"PICKME\"]", item.path("names").toString());

    JsonNode detail = data(admin.config(HttpMethod.GET, "/merchant-rules/" + item.path("id").asText(), null));
    assertEquals("^(?:UBER|PICKME)", detail.path("generated").path("pattern").asText());
    assertEquals("REGEX", detail.path("generated").path("matchType").asText());
    assertEquals(5, detail.path("generated").path("priority").asInt());

    ResponseEntity<String> list = admin.config(HttpMethod.GET, "/merchant-rules?q=UBER", null);
    assertFalse(list.getBody().contains("\"pattern\""), list.getBody());
  }

  @Test
  @DisplayName("test explains the result and never echoes the description; bad names are E.021")
  void testAndNames() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String cat = category(admin);
    ResponseEntity<String> t =
        admin.config(
            HttpMethod.POST,
            "/merchant-rules/test",
            "{\"rule\":{\"matchType\":\"REGEX\",\"names\":[\"CARGILLS\"],\"categoryId\":\"" + cat
                + "\",\"priorityLevel\":\"NORMAL\"},\"description\":\"CARGILLS FOOD CITY KIRULAPONE\"}");
    assertEquals(200, t.getStatusCode().value(), t.getBody());
    assertTrue(data(t).path("thisRuleMatches").asBoolean());
    assertFalse(t.getBody().contains("KIRULAPONE"), t.getBody());

    ResponseEntity<String> bad =
        admin.config(
            HttpMethod.POST,
            "/merchant-rules",
            "{\"mode\":\"SIMPLE\",\"matchType\":\"REGEX\",\"names\":[\"A\"],\"categoryId\":\"" + cat
                + "\",\"priorityLevel\":\"NORMAL\",\"active\":true}");
    assertEquals(422, bad.getStatusCode().value());
    assertEquals("MOI.BI.ADM.E.021", json(bad).path("error").asText());
  }
}
