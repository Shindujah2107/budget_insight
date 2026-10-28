package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiAuthOtp;
import com.moneyreserve.repository.BiAuthOtpRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
 * App sign-in end to end over HTTP: register, code, tokens, refresh rotation and reuse detection,
 * log-out, and the refusals (wrong, expired and over-guessed codes, resend throttle, missing or
 * forged identity).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("App sign-in: mobile number + SMS code, JWT sessions")
class AuthFlowIT {

  /**
   * The test profile sends codes through the log sender with this fixed code. A custom SMS bean
   * here would give this class its own Spring context, and a second context recreates the shared
   * in-memory schema under the other integration tests.
   */
  private static final String CODE = "123456";

  private static final AtomicInteger NEXT_NUMBER = new AtomicInteger(1000000);

  @Autowired private TestRestTemplate rest;
  @Autowired private BiAuthOtpRepository otps;
  @Autowired private ObjectMapper json;

  private String phone;

  @BeforeEach
  void freshNumber() {
    // A new number per test: the resend throttle is per number and the context is shared.
    phone = "+9477" + NEXT_NUMBER.getAndIncrement();
  }

  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("register -> code -> tokens; the access token reaches /me and the Insights API")
  void registerAndUseTheToken() throws Exception {
    ResponseEntity<String> sent = register("Test User", "0" + phone.substring(3));
    assertEquals(200, sent.getStatusCode().value(), sent.getBody());
    JsonNode codeSent = data(sent);
    assertEquals(phone, codeSent.get("phone").asText(), "Number is normalised to E.164");
    assertEquals("REGISTER", codeSent.get("purpose").asText());

    JsonNode session = data(verify(CODE, "REGISTER"));
    String access = session.get("accessToken").asText();
    assertEquals("Bearer", session.get("tokenType").asText());
    assertEquals(900, session.get("expiresIn").asLong());
    assertNotNull(session.get("refreshToken").asText());
    assertEquals("Test User", session.get("user").get("fullName").asText());

    ResponseEntity<String> me = call(HttpMethod.GET, "/api/auth/me", access, null);
    assertEquals(200, me.getStatusCode().value(), me.getBody());
    assertEquals(phone, data(me).get("phone").asText());

    // A customer endpoint accepts the token: whatever the feature flags answer, it is not a 401/403.
    ResponseEntity<String> summary = call(HttpMethod.GET, "/api/insights/summary", access, null);
    int status = summary.getStatusCode().value();
    assertTrue(status != 401 && status != 403, "Token refused by the Insights API: " + status);
  }

  @Test
  @DisplayName("a verified number cannot register again, and an unknown number cannot log in")
  void registerAndLoginAreSeparate() throws Exception {
    registerAndVerify();
    ResponseEntity<String> again = register("Someone Else", phone);
    assertEquals(409, again.getStatusCode().value());
    assertEquals("MR.AUT.E.002", error(again));

    ResponseEntity<String> unknown = post("/api/auth/login", Map.of("phone", "+94711111111"), null);
    assertEquals(404, unknown.getStatusCode().value());
    assertEquals("MR.AUT.E.003", error(unknown));
  }

  @Test
  @DisplayName("log in sends a LOGIN code that signs the same user in")
  void loginWithCode() throws Exception {
    JsonNode first = registerAndVerify();
    expireResendThrottle();
    ResponseEntity<String> sent = post("/api/auth/login", Map.of("phone", phone), null);
    assertEquals(200, sent.getStatusCode().value(), sent.getBody());
    assertEquals("LOGIN", data(sent).get("purpose").asText());

    JsonNode session = data(verify(CODE, "LOGIN"));
    assertEquals(first.get("user").get("id").asText(), session.get("user").get("id").asText());
  }

  @Test
  @DisplayName("a wrong code is refused; five wrong guesses burn the code, even the right one then")
  void attemptLimit() throws Exception {
    register("Test User", phone);
    String right = CODE;
    String wrong = "000000";

    for (int i = 1; i <= 4; i++) {
      ResponseEntity<String> refused = verify(wrong, "REGISTER");
      assertEquals(400, refused.getStatusCode().value());
      assertEquals("MR.AUT.E.004", error(refused));
    }
    ResponseEntity<String> fifth = verify(wrong, "REGISTER");
    assertEquals(429, fifth.getStatusCode().value());
    assertEquals("MR.AUT.E.006", error(fifth));

    ResponseEntity<String> tooLate = verify(right, "REGISTER");
    assertEquals(429, tooLate.getStatusCode().value(), "The burnt code must not work");
  }

  @Test
  @DisplayName("an expired code is refused with 410")
  void expiredCode() throws Exception {
    register("Test User", phone);
    BiAuthOtp otp = otps.findFirstByPhoneOrderByCreatedAtDesc(phone).orElseThrow();
    otp.setExpiresAt(OffsetDateTime.now().minusSeconds(1));
    otps.save(otp);

    ResponseEntity<String> refused = verify(CODE, "REGISTER");
    assertEquals(410, refused.getStatusCode().value());
    assertEquals("MR.AUT.E.005", error(refused));
  }

  @Test
  @DisplayName("a second code inside 30 seconds is refused with Retry-After")
  void resendThrottle() throws Exception {
    register("Test User", phone);
    ResponseEntity<String> tooSoon = register("Test User", phone);
    assertEquals(429, tooSoon.getStatusCode().value());
    assertEquals("MR.AUT.E.007", error(tooSoon));
    assertNotNull(tooSoon.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
  }

  @Test
  @DisplayName("refresh rotates; replaying a rotated token revokes the whole session")
  void refreshRotationAndReuse() throws Exception {
    String first = registerAndVerify().get("refreshToken").asText();

    ResponseEntity<String> rotated = post("/api/auth/refresh", Map.of("refreshToken", first), null);
    assertEquals(200, rotated.getStatusCode().value(), rotated.getBody());
    String second = data(rotated).get("refreshToken").asText();
    assertNotEquals(first, second);

    ResponseEntity<String> replay = post("/api/auth/refresh", Map.of("refreshToken", first), null);
    assertEquals(401, replay.getStatusCode().value());
    assertEquals("MR.AUT.E.008", error(replay));

    ResponseEntity<String> afterReuse =
        post("/api/auth/refresh", Map.of("refreshToken", second), null);
    assertEquals(401, afterReuse.getStatusCode().value(), "Reuse must revoke the newer token too");
  }

  @Test
  @DisplayName("log-out ends the session: its refresh token stops working")
  void logout() throws Exception {
    String refresh = registerAndVerify().get("refreshToken").asText();
    ResponseEntity<String> out = post("/api/auth/logout", Map.of("refreshToken", refresh), null);
    assertEquals(200, out.getStatusCode().value());

    ResponseEntity<String> refused = post("/api/auth/refresh", Map.of("refreshToken", refresh), null);
    assertEquals(401, refused.getStatusCode().value());
  }

  @Test
  @DisplayName("no token, a forged token, or bare X-User-Id headers are all refused")
  void unauthenticatedIsRejected() {
    ResponseEntity<String> none = call(HttpMethod.GET, "/api/auth/me", null, null);
    assertEquals(401, none.getStatusCode().value());

    ResponseEntity<String> forged =
        call(HttpMethod.GET, "/api/insights/summary", "eyJhbGciOiJIUzI1NiJ9.e30.forged", null);
    assertEquals(401, forged.getStatusCode().value());
    assertEquals("Bearer", forged.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));

    HttpHeaders spoof = new HttpHeaders();
    spoof.set("X-User-Id", "11111111-1111-4111-8111-111111111111");
    ResponseEntity<String> spoofed =
        rest.exchange("/api/insights/summary", HttpMethod.GET, new HttpEntity<>(spoof), String.class);
    assertTrue(
        spoofed.getStatusCode().value() == 401 || spoofed.getStatusCode().value() == 403,
        "An identity header without a token must not authenticate: "
            + spoofed.getStatusCode());
  }

  @Test
  @DisplayName("deleting the account erases its Insights data, ends its sessions and frees the number")
  void deleteAccount() throws Exception {
    JsonNode session = registerAndVerify();
    String access = session.get("accessToken").asText();
    String period = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Colombo")).toString();

    // Some Insights data of the customer's own, written through the normal API with the token.
    ResponseEntity<String> budget =
        call(HttpMethod.PUT, "/api/insights/budget", access, Map.of("period", period, "overallLimit", 50000));
    assertEquals(200, budget.getStatusCode().value(), budget.getBody());

    ResponseEntity<String> deleted = call(HttpMethod.DELETE, "/api/auth/account", access, null);
    assertEquals(200, deleted.getStatusCode().value(), deleted.getBody());
    JsonNode erased = data(deleted);
    assertTrue(erased.get("removedByTable").get("budgets").asInt() >= 1, deleted.getBody());
    assertTrue(erased.get("rowsRemoved").asLong() >= 1);

    // The still-unexpired access token stops working immediately, as does the refresh token.
    ResponseEntity<String> afterwards = call(HttpMethod.GET, "/api/insights/budget", access, null);
    assertEquals(401, afterwards.getStatusCode().value(), "A deleted account's token must not work");
    ResponseEntity<String> refresh =
        post("/api/auth/refresh", Map.of("refreshToken", session.get("refreshToken").asText()), null);
    assertEquals(401, refresh.getStatusCode().value());

    assertNull(otps.findFirstByPhoneOrderByCreatedAtDesc(phone).orElse(null));
    assertEquals(200, register("Test User", phone).getStatusCode().value(), "Number is free again");
  }

  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("the profile's name and email can be changed, and an empty email removes it")
  void updateProfile() throws Exception {
    String token = registerAndVerify().get("accessToken").asText();

    ResponseEntity<String> updated =
        call(
            HttpMethod.PATCH,
            "/api/auth/me",
            token,
            Map.of("fullName", "  Renamed User ", "email", "renamed@example.com"));
    assertEquals(200, updated.getStatusCode().value(), updated.getBody());
    assertEquals("Renamed User", data(updated).get("fullName").asText());
    assertEquals("renamed@example.com", data(updated).get("email").asText());

    JsonNode me = data(call(HttpMethod.GET, "/api/auth/me", token, null));
    assertEquals("Renamed User", me.get("fullName").asText());
    assertEquals(phone, me.get("phone").asText(), "the phone number is the login and stays");

    ResponseEntity<String> cleared =
        call(HttpMethod.PATCH, "/api/auth/me", token, Map.of("fullName", "Renamed User", "email", ""));
    assertEquals(200, cleared.getStatusCode().value(), cleared.getBody());
    assertTrue(data(cleared).path("email").isMissingNode() || data(cleared).get("email").isNull());

    ResponseEntity<String> badEmail =
        call(HttpMethod.PATCH, "/api/auth/me", token, Map.of("fullName", "Renamed User", "email", "not-an-email"));
    assertEquals(400, badEmail.getStatusCode().value());
    ResponseEntity<String> noName =
        call(HttpMethod.PATCH, "/api/auth/me", token, Map.of("fullName", " "));
    assertEquals(400, noName.getStatusCode().value());
    ResponseEntity<String> anonymous =
        call(HttpMethod.PATCH, "/api/auth/me", null, Map.of("fullName", "Someone"));
    assertEquals(401, anonymous.getStatusCode().value());
  }

  private JsonNode registerAndVerify() throws Exception {
    ResponseEntity<String> sent = register("Test User", phone);
    assertEquals(200, sent.getStatusCode().value(), sent.getBody());
    ResponseEntity<String> verified = verify(CODE, "REGISTER");
    assertEquals(200, verified.getStatusCode().value(), verified.getBody());
    return data(verified);
  }

  /** Back-dates the latest code so the next request is outside the 30 s resend window. */
  private void expireResendThrottle() {
    otps.findFirstByPhoneOrderByCreatedAtDesc(phone)
        .ifPresent(
            otp -> {
              otp.setCreatedAt(OffsetDateTime.now().minusMinutes(1));
              otps.save(otp);
            });
  }

  private ResponseEntity<String> register(String name, String number) {
    return post("/api/auth/register", Map.of("fullName", name, "phone", number), null);
  }

  private ResponseEntity<String> verify(String code, String purpose) {
    return post("/api/auth/verify", Map.of("phone", phone, "code", code, "purpose", purpose), null);
  }

  private ResponseEntity<String> post(String path, Object body, String token) {
    return call(HttpMethod.POST, path, token, body);
  }

  private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  private JsonNode data(ResponseEntity<String> response) throws Exception {
    return json.readTree(response.getBody()).get("data");
  }

  private String error(ResponseEntity<String> response) throws Exception {
    return json.readTree(response.getBody()).get("error").asText();
  }
}
