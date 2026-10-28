package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiComplianceEvent;
import com.moneyreserve.service.impl.DeviceIdHasher;
import com.moneyreserve.domain.ComplianceEventAction;
import com.moneyreserve.domain.ComplianceEventSource;
import com.moneyreserve.domain.ComplianceEventType;
import com.moneyreserve.repository.BiComplianceEventRepository;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Device-side OTP and PIN reporting, end to end (BI-36).
 *
 * <p>What these tests are actually holding is the shape of the stored row, not the status code. The
 * value of this route is that a regulator reading {@code bi_compliance_event} can tell a detection
 * the handset made from one the server made, can trust that the client did not assert any of it,
 * and can trust that a retried report is not a second incident. Each of those is a test below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Device compliance reporting")
class ComplianceEventsIT {

  private static final String EVENTS = "/api/insights/compliance/events";
  private static final String CONSENT = "/api/insights/consent";
  private static final String GRANT =
      "{\"consentVersion\":\"1.0\",\"policyHash\":\"bi36\",\"channel\":\"ANDROID\"}";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiComplianceEventRepository repository;

  @Autowired private DeviceIdHasher hasher;

  private final ObjectMapper json = new ObjectMapper();

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /** A signed-in caller and the handset it reports from; events are matched by that handset. */
  private record Principal(UUID userId, String deviceId) {}

  /** The handset this test's batches are reported from. */
  private final String currentDevice = "it-device-" + UUID.randomUUID();

  /** A caller who has consented. Every test needs one: the route is behind the consent gate. */
  private Principal consentedCaller() {
    Principal principal = new Principal(UUID.randomUUID(), currentDevice);
    ResponseEntity<String> granted =
        rest.exchange(CONSENT, HttpMethod.POST, authed(principal, GRANT), String.class);
    assertTrue(
        granted.getStatusCode().is2xxSuccessful(),
        "The fixture could not grant consent: " + granted.getStatusCode());
    return principal;
  }

  private HttpEntity<String> authed(Principal principal, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(principal.userId()));
    return new HttpEntity<>(body, headers);
  }

  private static String event(String type, OffsetDateTime at, String clientRef) {
    return "{\"eventType\":\""
        + type
        + "\",\"detectedAt\":\""
        + at
        + "\",\"clientRef\":\""
        + clientRef
        + "\"}";
  }

  private String batch(String... events) {
    return "{\"deviceId\":\"" + currentDevice + "\",\"platform\":\"ANDROID\",\"events\":["
        + String.join(",", events)
        + "]}";
  }

  private static String ref() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private JsonNode post(Principal principal, String body) throws Exception {
    ResponseEntity<String> response =
        rest.exchange(EVENTS, HttpMethod.POST, authed(principal, body), String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode(), response.getBody());
    return json.readTree(response.getBody()).path("data");
  }

  private List<BiComplianceEvent> deviceReported(Principal principal) {
    String device = hasher.hashDeviceId(principal.deviceId());
    return repository.findAll().stream()
        .filter(e -> device.equals(e.getDeviceIdHash()))
        .filter(e -> e.getSource() == ComplianceEventSource.SMS_PARSER)
        .collect(Collectors.toList());
  }

  // ---------------------------------------------------------------------------------
  // The tests
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A reported detection is stored as SMS_PARSER / DISCARDED, at the device's time")
  void reportsAreStoredWithTheServersOwnClaims() throws Exception {
    Principal caller = consentedCaller();
    // Truncated to milliseconds: occurred_at is timestamp(6), so a nanosecond-precision value
    // would not survive the round trip and the comparison below would fail on rounding alone.
    OffsetDateTime detectedAt =
        OffsetDateTime.now().minusHours(2).truncatedTo(ChronoUnit.MILLIS);
    String otpRef = ref();

    JsonNode data =
        post(
            caller,
            batch(
                event("OTP_DETECTED", detectedAt, otpRef),
                event("PIN_DETECTED", detectedAt.minusMinutes(5), ref())));

    assertEquals(2, data.path("recorded").asInt());
    assertEquals(0, data.path("rejected").asInt());
    assertEquals(0, data.path("duplicates").asInt());

    List<BiComplianceEvent> stored = deviceReported(caller);
    assertEquals(2, stored.size());
    for (BiComplianceEvent row : stored) {
      // The two claims the client never gets to make.
      assertEquals(ComplianceEventSource.SMS_PARSER, row.getSource());
      assertEquals(ComplianceEventAction.DISCARDED, row.getAction());
      assertNotNull(row.getClientRefHash());
      // The reference is stored as a digest, never as the value. This is what stops the one
      // client-controlled string becoming a place to put a code in the table that is never purged.
      assertEquals(64, row.getClientRefHash().length());
      assertFalse(
          row.getClientRefHash().contains(otpRef),
          "The raw client reference reached the compliance record");
    }

    BiComplianceEvent otp =
        stored.stream()
            .filter(r -> r.getEventType() == ComplianceEventType.OTP_DETECTED)
            .findFirst()
            .orElseThrow();
    assertEquals(
        detectedAt.toInstant(),
        otp.getOccurredAt().toInstant(),
        "occurred_at must be when the device detected it, not when the report arrived");
  }

  @Test
  @DisplayName("Resending the same batch reports duplicates and writes nothing new")
  void aRetryIsTheSameIncident() throws Exception {
    Principal caller = consentedCaller();
    String body =
        batch(
            event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(30), ref()),
            event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(20), ref()));

    assertEquals(2, post(caller, body).path("recorded").asInt());
    assertEquals(2, deviceReported(caller).size());

    JsonNode replay = post(caller, body);
    assertEquals(0, replay.path("recorded").asInt());
    assertEquals(2, replay.path("duplicates").asInt());
    assertEquals(
        2, deviceReported(caller).size(), "A replayed queue must not inflate the incident count");
  }

  @Test
  @DisplayName("The same reference twice inside one batch collapses to one incident")
  void inBatchDuplicatesCollapse() throws Exception {
    Principal caller = consentedCaller();
    String duplicated = ref();

    JsonNode data =
        post(
            caller,
            batch(
                event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(9), duplicated),
                event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(9), duplicated)));

    assertEquals(1, data.path("recorded").asInt());
    assertEquals(1, data.path("duplicates").asInt());
    assertEquals(1, deviceReported(caller).size());
  }

  @Test
  @DisplayName("A device cannot assert any event type but OTP_DETECTED and PIN_DETECTED")
  void onlyTwoTypesAreReportable() throws Exception {
    Principal caller = consentedCaller();

    JsonNode data =
        post(
            caller,
            batch(
                event("CONSENT_WITHDRAWN", OffsetDateTime.now().minusMinutes(1), ref()),
                event("ERASURE_COMPLETED", OffsetDateTime.now().minusMinutes(1), ref()),
                event("INGEST_RATE_LIMITED", OffsetDateTime.now().minusMinutes(1), ref()),
                event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(1), ref())));

    assertEquals(3, data.path("rejected").asInt());
    assertEquals(1, data.path("recorded").asInt(), "One bad row must not cost the good one");
    for (JsonNode result : data.path("results")) {
      if ("REJECTED".equals(result.path("status").asText())) {
        assertEquals("MR.CMP.E.003", result.path("error").asText());
      }
    }
    assertEquals(1, deviceReported(caller).size());
  }

  @Test
  @DisplayName("A detection dated in the future or outside the lookback is refused")
  void implausibleTimestampsAreRefused() throws Exception {
    Principal caller = consentedCaller();

    JsonNode data =
        post(
            caller,
            batch(
                event("OTP_DETECTED", OffsetDateTime.now().plusDays(1), ref()),
                event("OTP_DETECTED", OffsetDateTime.now().minusYears(2), ref())));

    assertEquals(2, data.path("rejected").asInt());
    assertEquals(0, data.path("recorded").asInt());
    for (JsonNode result : data.path("results")) {
      assertEquals("MR.CMP.E.004", result.path("error").asText());
    }
    assertTrue(deviceReported(caller).isEmpty());
  }

  @Test
  @DisplayName("A caller who has not consented cannot report")
  void consentIsRequired() {
    Principal stranger = new Principal(UUID.randomUUID(), currentDevice);

    ResponseEntity<String> response =
        rest.exchange(
            EVENTS,
            HttpMethod.POST,
            authed(stranger, batch(event("OTP_DETECTED", OffsetDateTime.now(), ref()))),
            String.class);

    assertFalse(
        response.getStatusCode().is2xxSuccessful(),
        "Reporting without consent must be refused: " + response.getBody());
    assertTrue(
        response.getBody() != null && response.getBody().contains("MR.CNS.E.001"),
        "Expected the consent code, got: " + response.getBody());
    assertTrue(deviceReported(stranger).isEmpty());
  }

  @Test
  @DisplayName("An oversized batch answers 400, not 429 — waiting would not make it smaller")
  void oversizedBatchIsRefused() {
    Principal caller = consentedCaller();
    StringBuilder events = new StringBuilder();
    for (int i = 0; i < 201; i++) {
      if (i > 0) {
        events.append(',');
      }
      events.append(event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(1), ref()));
    }

    ResponseEntity<String> response =
        rest.exchange(
            EVENTS,
            HttpMethod.POST,
            authed(
                caller,
                "{\"deviceId\":\"it-device-0001\",\"events\":[" + events + "]}"),
            String.class);

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertTrue(response.getBody() != null && response.getBody().contains("MR.CMP.E.001"));
    assertTrue(deviceReported(caller).isEmpty());
  }

  @Test
  @DisplayName("A malformed event is rejected on its own — the batch still returns 200 with results")
  void aMalformedEventDoesNotFailTheRequest() throws Exception {
    Principal caller = consentedCaller();

    // The proof that the contract does not cascade @Valid into the list. If it did, this would be
    // a 400 naming no event and carrying no results, and a device could only guess which row to
    // drop -- over incidents whose source messages no longer exist anywhere.
    JsonNode data =
        post(
            caller,
            batch(
                event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(3), "short"),
                event("OTP_DETECTED", OffsetDateTime.now().minusMinutes(3), ref())));

    assertEquals(1, data.path("rejected").asInt());
    assertEquals(1, data.path("recorded").asInt());
    assertEquals(2, data.path("results").size());
    assertEquals(1, deviceReported(caller).size());
  }
}
