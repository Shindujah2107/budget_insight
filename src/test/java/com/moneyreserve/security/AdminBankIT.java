package com.moneyreserve.security;

import static com.moneyreserve.security.AdminTestSupport.data;
import static com.moneyreserve.security.AdminTestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Bank registry (/admin/config/banks): a bank added from the dashboard is offered immediately,
 * senders can be drafted for it, and it cannot be turned off or deleted while something uses it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: bank registry")
class AdminBankIT {

  @Autowired private TestRestTemplate rest;

  /**
   * A fresh 4-digit code per call. The Spring context (and its H2 database) is shared by the tests
   * in this class, and banks they create stay behind, so codes must not repeat within a run.
   */
  private static final AtomicInteger NEXT_CODE =
      new AtomicInteger(8000 + (int) (Math.abs(System.nanoTime()) % 500));

  private static String code() {
    return String.valueOf(NEXT_CODE.getAndIncrement());
  }

  private static boolean listed(JsonNode banks, String code) {
    for (JsonNode b : banks) {
      if (code.equals(b.path("bankCode").asText())) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("the seeded banks are listed, active, with usage")
  void seededBanksListed() throws Exception {
    JsonNode banks = data(new AdminTestSupport(rest, UUID.randomUUID()).config(HttpMethod.GET, "/banks", null));
    assertTrue(listed(banks, "7083"), banks.toString());
    assertTrue(banks.get(0).path("active").asBoolean());
    assertTrue(banks.get(0).has("usage"));
  }

  @Test
  @DisplayName("a new bank is offered at once, takes senders, and is protected while in use")
  void newBankLifecycle() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String code = code();
    String name = "Test Bank " + code;

    ResponseEntity<String> created =
        admin.config(HttpMethod.POST, "/banks", "{\"bankCode\":\"" + code + "\",\"bankName\":\"" + name + "\"}");
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    assertTrue(listed(data(admin.config(HttpMethod.GET, "/banks", null)), code), "offered in the dropdown at once");

    ResponseEntity<String> dup =
        admin.config(HttpMethod.POST, "/banks", "{\"bankCode\":\"" + code + "\",\"bankName\":\"Other " + code + "\"}");
    assertEquals(409, dup.getStatusCode().value());
    assertEquals("MR.ADM.E.008", json(dup).path("error").asText());

    ResponseEntity<String> sender =
        admin.config(
            HttpMethod.POST,
            "/senders",
            "{\"bankCode\":\"" + code + "\",\"senderId\":\"TB" + code + "\",\"countryCode\":\"LK\",\"active\":true}");
    assertEquals(201, sender.getStatusCode().value(), sender.getBody());

    ResponseEntity<String> off = admin.config(HttpMethod.PUT, "/banks/" + code, "{\"active\":false}");
    assertEquals(409, off.getStatusCode().value());
    assertEquals("MR.ADM.E.023", json(off).path("error").asText());
    ResponseEntity<String> del = admin.config(HttpMethod.DELETE, "/banks/" + code, null);
    assertEquals(409, del.getStatusCode().value());

    ResponseEntity<String> renamed = admin.config(HttpMethod.PUT, "/banks/" + code, "{\"bankName\":\"" + name + " PLC\"}");
    assertEquals(200, renamed.getStatusCode().value(), renamed.getBody());
    assertEquals(name + " PLC", data(renamed).path("bankName").asText());
    // a live bank's edit is a draft: the live name stays until publish
    assertEquals("DRAFT_EDITED", data(renamed).path("state").asText());
    assertEquals(name, data(renamed).path("published").path("bankName").asText());
    assertTrue(data(renamed).path("changesetRevision").isNumber());

    // leave the shared changeset as found
    String changeId = data(sender).path("item").path("pendingChangeId").asText();
    assertEquals(200, admin.config(HttpMethod.DELETE, "/changeset/changes/" + changeId, null).getStatusCode().value());

    // now unused: it can be turned off (and leaves the dropdown), then deleted
    assertEquals(200, admin.config(HttpMethod.PUT, "/banks/" + code, "{\"active\":false}").getStatusCode().value());
    assertFalse(listed(data(admin.config(HttpMethod.GET, "/banks", null)), code));
    assertTrue(listed(data(admin.config(HttpMethod.GET, "/banks?active=all", null)), code));
    ResponseEntity<String> refused =
        admin.config(
            HttpMethod.POST,
            "/senders",
            "{\"bankCode\":\"" + code + "\",\"senderId\":\"TB2" + code + "\",\"countryCode\":\"LK\",\"active\":true}");
    assertEquals(400, refused.getStatusCode().value(), "no new senders for a turned-off bank");
    assertEquals(200, admin.config(HttpMethod.DELETE, "/banks/" + code, null).getStatusCode().value());
    JsonNode deleting = find(data(admin.config(HttpMethod.GET, "/banks?active=all", null)), code);
    assertEquals("DRAFT_DELETED", deleting.path("state").asText(), "deleted with the next publish");

    // leave the shared changeset as found
    assertEquals(
        200,
        admin.config(HttpMethod.DELETE, "/changeset/changes/" + deleting.path("pendingChangeId").asText(), null)
            .getStatusCode()
            .value());
    assertEquals("PUBLISHED", find(data(admin.config(HttpMethod.GET, "/banks?active=all", null)), code).path("state").asText());
  }

  @Test
  @DisplayName("a name a pending rename will take cannot be used by another bank")
  void pendingRenameReservesName() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String a = code();
    String b = code();
    assertEquals(201, admin.config(HttpMethod.POST, "/banks", "{\"bankCode\":\"" + a + "\",\"bankName\":\"Alpha " + a + "\"}").getStatusCode().value());
    ResponseEntity<String> renamed = admin.config(HttpMethod.PUT, "/banks/" + a, "{\"bankName\":\"Reserved " + a + "\"}");
    assertEquals(200, renamed.getStatusCode().value(), renamed.getBody());
    ResponseEntity<String> clash =
        admin.config(HttpMethod.POST, "/banks", "{\"bankCode\":\"" + b + "\",\"bankName\":\"reserved " + a + "\"}");
    assertEquals(409, clash.getStatusCode().value(), clash.getBody());
    assertEquals("MR.ADM.E.008", json(clash).path("error").asText());
    // leave the shared changeset as found
    String changeId = data(renamed).path("pendingChangeId").asText();
    assertEquals(200, admin.config(HttpMethod.DELETE, "/changeset/changes/" + changeId, null).getStatusCode().value());
  }

  @Test
  @DisplayName("bank code must be the 4-digit CBSL code")
  void codeValidated() throws Exception {
    ResponseEntity<String> r =
        new AdminTestSupport(rest, UUID.randomUUID())
            .config(HttpMethod.POST, "/banks", "{\"bankCode\":\"71A5\",\"bankName\":\"Bad Code Bank\"}");
    assertEquals(400, r.getStatusCode().value());
    assertEquals("MR.ADM.E.006", json(r).path("error").asText());
  }

  @Test
  @DisplayName("Add bank SMS sender can add a new bank; discarding the draft removes it")
  void inlineNewBank() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String code = code();

    ResponseEntity<String> created =
        admin.config(
            HttpMethod.POST,
            "/senders",
            "{\"newBank\":{\"bankCode\":\"" + code + "\",\"bankName\":\"Inline Bank " + code + "\"},"
                + "\"senderId\":\"IB" + code + "\",\"countryCode\":\"LK\",\"active\":true}");
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    assertEquals(code, data(created).path("item").path("effective").path("bankCode").asText());
    JsonNode bank = find(data(admin.config(HttpMethod.GET, "/banks", null)), code);
    assertTrue(bank.path("pending").asBoolean(), "pending until published");
    assertFalse(bank.has("type"), "banks only: no type field");

    ResponseEntity<String> noCode =
        admin.config(
            HttpMethod.POST,
            "/senders",
            "{\"newBank\":{\"bankName\":\"No Code " + code + "\"},"
                + "\"senderId\":\"NC" + code + "\",\"countryCode\":\"LK\",\"active\":true}");
    assertEquals(400, noCode.getStatusCode().value(), "a new bank needs its 4-digit code");

    ResponseEntity<String> both =
        admin.config(
            HttpMethod.POST,
            "/senders",
            "{\"bankCode\":\"7010\",\"newBank\":{\"bankCode\":\"" + code + "\",\"bankName\":\"Both " + code + "\"},"
                + "\"senderId\":\"BO" + code + "\",\"countryCode\":\"LK\",\"active\":true}");
    assertEquals(400, both.getStatusCode().value());

    String changeId = data(created).path("item").path("pendingChangeId").asText();
    assertEquals(200, admin.config(HttpMethod.DELETE, "/changeset/changes/" + changeId, null).getStatusCode().value());
    assertFalse(listed(data(admin.config(HttpMethod.GET, "/banks?active=all", null)), code), "pending bank removed with its draft");
  }

  private static JsonNode find(JsonNode banks, String code) {
    for (JsonNode b : banks) {
      if (code.equals(b.path("bankCode").asText())) {
        return b;
      }
    }
    throw new AssertionError(code + " not listed in " + banks);
  }
}
