package com.moneyreserve.service.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.AuthProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Text.lk's send-SMS call, against a local stand-in for its API. */
class TextLkSmsSenderTest {

  private static final String TOKEN = "test-token-not-a-real-secret";
  private final ObjectMapper json = new ObjectMapper();
  private HttpServer server;
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String reply =
      "{\"status\":\"success\",\"message\":\"Your message was successfully delivered\","
          + "\"data\":{\"uid\":\"abc123\",\"status\":\"Delivered\"}}";

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          path.set(exchange.getRequestURI().getPath());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out = reply.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status, out.length);
          exchange.getResponseBody().write(out);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private TextLkSmsSender sender() {
    AuthProperties.TextLk config = new AuthProperties.TextLk();
    config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3/");
    config.setApiToken(TOKEN);
    config.setSenderId("MoneyRsrv");
    config.setTimeout(Duration.ofSeconds(5));
    return new TextLkSmsSender(config, json, HttpClient.newHttpClient());
  }

  @Test
  @DisplayName("sends a plain SMS to the number without its plus, with the bearer token")
  void sends() throws Exception {
    sender().send("+94771234567", "Your MoneyReserve code is 482913.");

    assertEquals("/api/v3/sms/send", path.get());
    assertEquals("Bearer " + TOKEN, authorization.get());
    JsonNode sent = json.readTree(body.get());
    assertEquals("94771234567", sent.get("recipient").asText());
    assertEquals("MoneyRsrv", sent.get("sender_id").asText());
    assertEquals("plain", sent.get("type").asText());
    assertEquals("Your MoneyReserve code is 482913.", sent.get("message").asText());
  }

  @Test
  @DisplayName("an error reply from Text.lk fails the send, without the token or the code in it")
  void refused() {
    reply = "{\"status\":\"error\",\"message\":\"Sender ID not approved\"}";
    RuntimeException ex =
        assertThrows(
            TextLkSmsSender.SmsSendException.class,
            () -> sender().send("+94771234567", "Your MoneyReserve code is 482913."));
    assertTrue(ex.getMessage().contains("Sender ID not approved"));
    assertFalse(ex.getMessage().contains(TOKEN));
    assertFalse(ex.getMessage().contains("482913"));
  }

  @Test
  @DisplayName("an HTTP failure fails the send")
  void httpError() {
    status = 500;
    reply = "oops";
    assertThrows(
        TextLkSmsSender.SmsSendException.class, () -> sender().send("+94771234567", "code 1"));
  }

  @Test
  @DisplayName("an unreachable Text.lk fails the send")
  void unreachable() {
    TextLkSmsSender sender = sender();
    server.stop(0);
    assertThrows(
        TextLkSmsSender.SmsSendException.class, () -> sender.send("+94771234567", "code 1"));
  }
}
