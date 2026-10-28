package com.moneyreserve.security;

import static com.moneyreserve.security.AdminTestSupport.data;
import static com.moneyreserve.security.AdminTestSupport.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneyreserve.repository.BiConfigChangeRepository;
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
 * Template wizard (T3, T4, T6 BUILDER): the sample is never stored or echoed, OTP samples are
 * refused, and a BUILDER save regenerates the pattern from the spec (architecture doc §7.7.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: template builder privacy and save")
class AdminTemplateBuilderIT {

  private static final String SAMPLE =
      "LKR 2,450.00 debited to Ac No:12XXXXXX34 on 27/09/26 14:02:19 Reason:KEELLS SUPERMARKET NUGEGODA Bal:";
  private static final String ANNOTATIONS =
      "[{\"start\":4,\"end\":12,\"field\":\"AMOUNT\"},"
          + "{\"start\":13,\"end\":20,\"field\":\"DIRECTION\",\"direction\":\"DEBIT\",\"counterpartWords\":[\"credited\"]},"
          + "{\"start\":30,\"end\":40,\"field\":\"IGNORE\",\"ignoreKind\":\"MASKED_ACCOUNT\"},"
          + "{\"start\":44,\"end\":52,\"field\":\"DATE\",\"format\":\"dd/MM/yy\"},"
          + "{\"start\":53,\"end\":61,\"field\":\"TIME\",\"format\":\"HH:mm:ss\"},"
          + "{\"start\":69,\"end\":96,\"field\":\"MERCHANT\"}]";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiConfigChangeRepository changes;

  @Test
  @DisplayName("analyse and generate never echo the sample; the saved draft holds no sample text")
  void sampleNeverStoredOrEchoed() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    ResponseEntity<String> analysed =
        admin.config(HttpMethod.POST, "/templates/builder/analyse", "{\"bankCode\":\"7083\",\"sample\":\"" + SAMPLE + "\"}");
    assertEquals(200, analysed.getStatusCode().value(), analysed.getBody());
    assertFalse(analysed.getBody().contains("KEELLS"), analysed.getBody());

    String type = "WIZ_" + System.nanoTime() % 100000;
    ResponseEntity<String> generated =
        admin.config(
            HttpMethod.POST,
            "/templates/builder/generate",
            "{\"bankCode\":\"7083\",\"messageType\":\"" + type + "\",\"logicalType\":\"ACCOUNT_TRANSACTION\","
                + "\"instrumentType\":\"BANK\",\"senders\":[\"HNB\"],\"sample\":\"" + SAMPLE + "\",\"annotations\":"
                + ANNOTATIONS + ",\"redactionConfirmed\":true}");
    assertEquals(200, generated.getStatusCode().value(), generated.getBody());
    assertFalse(generated.getBody().contains(SAMPLE), generated.getBody());
    JsonNode spec = data(generated).path("builderSpec");

    ResponseEntity<String> saved =
        admin.config(
            HttpMethod.POST,
            "/templates",
            "{\"mode\":\"BUILDER\",\"bankCode\":\"7083\",\"messageType\":\"" + type + "\",\"builderSpec\":" + spec
                + ",\"pattern\":\"^ignored\",\"active\":true}");
    assertEquals(201, saved.getStatusCode().value(), saved.getBody());
    changes.findAll()
        .forEach(
            c -> {
              String payload = String.valueOf(c.getPayload());
              assertFalse(payload.contains("KEELLS") || payload.contains("2,450.00") || payload.contains("^ignored"), payload);
            });

    // The draft reads from sender HNB, which the test schema has no active row for: left pending,
    // it would block every later publish-all in this shared context (TEMPLATE_SENDER_INACTIVE).
    String changeId = data(saved).path("item").path("pendingChangeId").asText();
    ResponseEntity<String> discarded =
        admin.config(HttpMethod.DELETE, "/changeset/changes/" + changeId, null);
    assertEquals(200, discarded.getStatusCode().value(), discarded.getBody());
  }

  @Test
  @DisplayName("an OTP sample is refused with E.018 and not echoed")
  void otpRefused() throws Exception {
    ResponseEntity<String> r =
        new AdminTestSupport(rest, UUID.randomUUID())
            .config(
                HttpMethod.POST,
                "/templates/builder/analyse",
                "{\"bankCode\":\"7083\",\"sample\":\"425401 is the one-time password (OTP) for LKR 1,287.00\"}");
    assertEquals(422, r.getStatusCode().value());
    assertEquals("MR.ADM.E.018", json(r).path("error").asText());
    assertFalse(r.getBody().contains("425401"), r.getBody());
  }

  @Test
  @DisplayName("a sample an existing template (even a draft) already reads is flagged and cannot be generated again")
  void duplicateFormatRefused() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    String type = "DUP_" + System.nanoTime() % 100000;
    String generateBody =
        "{\"bankCode\":\"7083\",\"messageType\":\"%s\",\"logicalType\":\"ACCOUNT_TRANSACTION\","
            + "\"instrumentType\":\"BANK\",\"senders\":[\"HNB\"],\"sample\":\"" + SAMPLE + "\",\"annotations\":"
            + ANNOTATIONS + ",\"redactionConfirmed\":true%s}";
    ResponseEntity<String> generated =
        admin.config(HttpMethod.POST, "/templates/builder/generate", String.format(generateBody, type, ""));
    assertEquals(200, generated.getStatusCode().value(), generated.getBody());
    ResponseEntity<String> saved =
        admin.config(
            HttpMethod.POST,
            "/templates",
            "{\"mode\":\"BUILDER\",\"bankCode\":\"7083\",\"messageType\":\"" + type + "\",\"builderSpec\":"
                + data(generated).path("builderSpec") + ",\"active\":true}");
    assertEquals(201, saved.getStatusCode().value(), saved.getBody());
    String templateId = data(saved).path("item").path("id").asText();
    String changeId = data(saved).path("item").path("pendingChangeId").asText();
    try {
      // The paste screen is told, with enough to name the template.
      ResponseEntity<String> analysed =
          admin.config(HttpMethod.POST, "/templates/builder/analyse", "{\"bankCode\":\"7083\",\"sample\":\"" + SAMPLE + "\"}");
      assertEquals(200, analysed.getStatusCode().value(), analysed.getBody());
      JsonNode match = data(analysed).path("existingTemplateMatch");
      assertEquals(type, match.path("messageType").asText(), analysed.getBody());
      assertTrue(match.path("sameBank").asBoolean(), analysed.getBody());
      assertTrue(match.path("draft").asBoolean(), analysed.getBody());
      assertFalse(analysed.getBody().contains("KEELLS"), analysed.getBody());

      // The same format cannot be built a second time for the bank...
      ResponseEntity<String> again =
          admin.config(HttpMethod.POST, "/templates/builder/generate", String.format(generateBody, type + "_2", ""));
      assertEquals(409, again.getStatusCode().value(), again.getBody());
      assertEquals("MR.ADM.E.008", json(again).path("error").asText());

      // ...but editing that template may read its own sample.
      ResponseEntity<String> self =
          admin.config(
              HttpMethod.POST,
              "/templates/builder/analyse",
              "{\"bankCode\":\"7083\",\"sample\":\"" + SAMPLE + "\",\"templateId\":\"" + templateId + "\"}");
      assertTrue(data(self).path("existingTemplateMatch").isNull(), self.getBody());
      ResponseEntity<String> edit =
          admin.config(
              HttpMethod.POST,
              "/templates/builder/generate",
              String.format(generateBody, type, ",\"templateId\":\"" + templateId + "\""));
      assertEquals(200, edit.getStatusCode().value(), edit.getBody());
    } finally {
      // Left pending, the draft would block later publish-all tests in this shared context.
      admin.config(HttpMethod.DELETE, "/changeset/changes/" + changeId, null);
    }
  }
}
