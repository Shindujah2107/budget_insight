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
 * SMS format discovery as it ships: both flags off and no user-hash key. The existing flows it
 * touches — consent withdrawal, erasure, reference sync — must behave exactly as before, and the
 * new routes must answer 501 so a phone holds its queue.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "moneyreserve.insights.format-discovery.enabled=false",
      "moneyreserve.insights.format-discovery.samples-enabled=false",
      "moneyreserve.insights.format-discovery.user-hash-key="
    })
@ActiveProfiles("test")
@DisplayName("SMS format discovery switched off (the shipped default)")
class FormatDiscoveryFlagsOffIT {

  private static final String GRANT =
      "{\"consentVersion\":\"1.0\",\"policyHash\":\"off\",\"channel\":\"ANDROID\"}";

  @Autowired private TestRestTemplate rest;

  private final ObjectMapper json = new ObjectMapper();

  @Test
  @DisplayName("the new routes answer 501, so a phone keeps its queue")
  void routesAreNotServing() {
    UUID user = consented();
    assertEquals(
        501,
        call("/api/insights/formats/unmatched-stats", HttpMethod.POST, user,
                "{\"deviceId\":\"d\",\"stats\":[{\"senderId\":\"NTB\",\"day\":\"2026-10-07\",\"count\":1}]}")
            .getStatusCode()
            .value());
    assertEquals(
        501,
        call("/api/insights/formats/samples", HttpMethod.POST, user,
                "{\"deviceId\":\"d\",\"vocabularyVersion\":1,\"samples\":[{\"clientRef\":\"abcdefgh\",\"senderId\":\"NTB\",\"tokens\":[\"W\",\"W\",\"W\",\"W\"]}]}")
            .getStatusCode()
            .value());
  }

  @Test
  @DisplayName("consent withdrawal answers as before")
  void withdrawalUnchanged() throws Exception {
    UUID user = consented();
    ResponseEntity<String> r = call("/api/insights/consent", HttpMethod.DELETE, user, null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    assertTrue(json.readTree(r.getBody()).path("success").asBoolean());
  }

  @Test
  @DisplayName("erasure works and reports no format samples")
  void erasureUnchanged() throws Exception {
    UUID user = consented();
    ResponseEntity<String> r = call("/api/insights/data", HttpMethod.DELETE, user, null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    assertEquals(0, json.readTree(r.getBody()).path("data").path("removedByTable").path("formatSamples").asInt());
  }

  @Test
  @DisplayName("reference sync never carries a vocabulary, even when a phone asks")
  void syncUnchanged() throws Exception {
    UUID user = consented();
    JsonNode body =
        json.readTree(
            call("/api/insights/reference/sync?deviceId=d&vocabularyVersion=0", HttpMethod.GET, user, null).getBody());
    assertTrue(body.path("success").asBoolean(), body.toString());
    assertTrue(body.path("data").path("vocabulary").isMissingNode(), body.toString());
  }

  private UUID consented() {
    UUID user = UUID.randomUUID();
    assertTrue(call("/api/insights/consent", HttpMethod.POST, user, GRANT).getStatusCode().is2xxSuccessful());
    return user;
  }

  private ResponseEntity<String> call(String path, HttpMethod method, UUID user, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(user));
    if (body != null) {
      h.setContentType(MediaType.APPLICATION_JSON);
    }
    return rest.exchange(path, method, new HttpEntity<>(body, h), String.class);
  }
}
