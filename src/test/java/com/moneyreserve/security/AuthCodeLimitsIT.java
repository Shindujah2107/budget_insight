package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.AuthProperties;
import com.moneyreserve.domain.BiAuthCodeRequest;
import com.moneyreserve.repository.BiAuthCodeRequestRepository;
import com.moneyreserve.service.sms.SmsSender;
import com.moneyreserve.service.sms.TextLkSmsSender;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Sign-in code request limits (V1.32.0): a send that fails still counts for its number; one network
 * address can only ask so many codes per hour; and a daily total stops SMS (with an alert) when
 * reached. All three answer with the codes the app already shows in English, Sinhala and Tamil.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AuthCodeLimitsIT.FlakySms.class)
@DisplayName("Sign-in code request limits")
class AuthCodeLimitsIT {

  /** An SMS sender that fails on demand, like Text.lk during an outage. */
  @TestConfiguration
  static class FlakySms {
    static final AtomicBoolean FAIL = new AtomicBoolean(false);

    @Bean
    @Primary
    SmsSender flakySmsSender() {
      return (phone, text) -> {
        if (FAIL.get()) {
          throw new TextLkSmsSender.SmsSendException("Text.lk could not be reached (test)");
        }
      };
    }
  }

  private static final AtomicInteger NEXT = new AtomicInteger(5_100_000);

  @Autowired private TestRestTemplate rest;
  @Autowired private AuthProperties auth;
  @Autowired private BiAuthCodeRequestRepository requests;
  @Autowired private ObjectMapper json;

  @AfterEach
  void restore() {
    FlakySms.FAIL.set(false);
    auth.getOtp().setMaxSendsPerHour(10);
    auth.getOtp().setMaxSendsPerHourPerIp(0);
    auth.getOtp().setMaxSendsPerDay(0);
  }

  private static String number() {
    return "+9476" + NEXT.getAndIncrement();
  }

  private ResponseEntity<String> register(String phone) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    return rest.exchange(
        "/api/auth/register", HttpMethod.POST, new HttpEntity<>(Map.of("fullName", "Limit Test", "phone", phone), h), String.class);
  }

  private String error(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("error").asText();
  }

  @Test
  @DisplayName("failed sends count: retrying through an SMS outage stops at the hourly limit for that number")
  void failedSendsCount() throws Exception {
    auth.getOtp().setMaxSendsPerHour(3);
    FlakySms.FAIL.set(true);
    String phone = number();
    for (int i = 0; i < 3; i++) {
      ResponseEntity<String> r = register(phone);
      assertEquals(503, r.getStatusCode().value(), r.getBody());
      assertEquals("MR.AUT.E.009", error(r), "couldn't send: try again shortly");
    }
    ResponseEntity<String> fourth = register(phone);
    assertEquals(429, fourth.getStatusCode().value(), fourth.getBody());
    assertEquals("MR.AUT.E.006", error(fourth));
    assertNotNull(fourth.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    FlakySms.FAIL.set(false);
    assertEquals(429, register(phone).getStatusCode().value(), "still limited once SMS works again");
  }

  @Test
  @DisplayName("one network address: after its hourly limit, codes for any other number are refused too")
  void perAddressLimit() throws Exception {
    assertEquals(200, register(number()).getStatusCode().value());
    BiAuthCodeRequest last = requests.findAll().stream().max(Comparator.comparing(BiAuthCodeRequest::getCreatedAt)).orElseThrow();
    assertNotNull(last.getIpHash(), "the caller's address is counted (as a hash)");
    assertTrue(!last.getIpHash().contains("127.0.0.1") && last.getPhoneHash().length() == 64, "hashes only");
    long fromHere = requests.countByIpHashAndCreatedAtAfter(last.getIpHash(), OffsetDateTime.now().minusHours(1));
    auth.getOtp().setMaxSendsPerHourPerIp((int) fromHere + 1);

    assertEquals(200, register(number()).getStatusCode().value(), "one more is allowed");
    ResponseEntity<String> refused = register(number());
    assertEquals(429, refused.getStatusCode().value(), refused.getBody());
    assertEquals("MR.AUT.E.006", error(refused));
    assertNotNull(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("daily cap: once reached, no more sign-in SMS today (\"try again shortly\")")
  void dailyCap() throws Exception {
    long today = requests.countByCreatedAtAfter(OffsetDateTime.now().minusDays(1));
    auth.getOtp().setMaxSendsPerDay((int) today + 1);
    assertEquals(200, register(number()).getStatusCode().value());
    ResponseEntity<String> capped = register(number());
    assertEquals(503, capped.getStatusCode().value(), capped.getBody());
    assertEquals("MR.AUT.E.009", error(capped));
  }
}
