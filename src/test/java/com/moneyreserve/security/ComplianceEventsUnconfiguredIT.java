package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.util.UUID;
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
 * Device compliance reporting with no device hash salt configured.
 *
 * <p>This route has no release flag; the salt is the only thing that can withhold it, and it does
 * so for a reason that is not a release decision. Without a salt the client reference cannot be
 * stored as a digest the client could not reverse, and {@code bi_compliance_event} is never purged.
 *
 * <p><b>501, not 500 and not 200.</b> 501 is the answer that makes a handset hold its queue rather
 * than discard it, which matters more here than anywhere else in the service: the messages that
 * produced these incidents were destroyed on the phone, so a dropped queue is unrecoverable. The
 * moment the salt is set the same queue is accepted, with no client change and no release.
 *
 * <p>The service must also not have booted silently into this state — {@code
 * ComplianceReportingGuard} logs it at ERROR — but a log line is not what this test asserts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moneyreserve.insights.device-hash-salt=")
@ActiveProfiles("test")
@DisplayName("Device compliance reporting — no salt configured")
class ComplianceEventsUnconfiguredIT {

  static final String EVENTS = "/api/insights/compliance/events";

  @Autowired private TestRestTemplate rest;

  /** One well-formed report, so the status under test is the guard and not the body. */
  static HttpEntity<String> authedReport() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    return new HttpEntity<>(
        "{\"deviceId\":\"it-device-0001\",\"events\":[{\"eventType\":\"OTP_DETECTED\","
            + "\"detectedAt\":\""
            + OffsetDateTime.now().minusMinutes(5)
            + "\",\"clientRef\":\"nosaltclientref01\"}]}",
        headers);
  }

  @Test
  @DisplayName("Reporting answers 501 — the device holds its queue until the salt is set")
  void answers501() {
    ResponseEntity<String> response =
        rest.exchange(EVENTS, HttpMethod.POST, authedReport(), String.class);

    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.getStatusCode());
  }
}
