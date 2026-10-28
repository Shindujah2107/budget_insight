package com.moneyreserve.service.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.AuthProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends sign-in codes as real SMS through Text.lk ({@code POST /sms/send}, bearer API token).
 *
 * <p>Selected with {@code moneyreserve.auth.sms.provider=textlk}. Neither the API token nor the
 * message (it carries the code) is ever logged; a failure is reported by HTTP status and Text.lk's
 * own error text only.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "moneyreserve.auth.sms.provider", havingValue = "textlk")
public class TextLkSmsSender implements SmsSender {

  private final AuthProperties.TextLk config;
  private final ObjectMapper json;
  private final HttpClient http;

  @Autowired
  public TextLkSmsSender(AuthProperties auth, ObjectMapper json) {
    this(
        auth.getSms().getTextLk(),
        json,
        HttpClient.newBuilder().connectTimeout(auth.getSms().getTextLk().getTimeout()).build());
  }

  TextLkSmsSender(AuthProperties.TextLk config, ObjectMapper json, HttpClient http) {
    this.config = config;
    this.json = json;
    this.http = http;
  }

  @Override
  public void send(String phone, String text) {
    // Text.lk wants the number without the plus: 94771234567.
    String recipient = phone.startsWith("+") ? phone.substring(1) : phone;
    HttpResponse<String> response;
    try {
      String body =
          json.writeValueAsString(
              Map.of(
                  "recipient", recipient,
                  "sender_id", config.getSenderId(),
                  "type", "plain",
                  "message", text));
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(config.getBaseUrl().replaceAll("/+$", "") + "/sms/send"))
              .timeout(config.getTimeout())
              .header("Authorization", "Bearer " + config.getApiToken())
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      response = http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException ex) {
      throw new SmsSendException("Text.lk could not be reached (" + ex.getClass().getSimpleName() + ")");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new SmsSendException("Sending the SMS was interrupted");
    }

    JsonNode reply = readQuietly(response.body());
    if (response.statusCode() / 100 != 2
        || !"success".equalsIgnoreCase(reply.path("status").asText())) {
      throw new SmsSendException(
          "Text.lk refused the message: HTTP "
              + response.statusCode()
              + " "
              + reply.path("message").asText("").replaceAll("\\s+", " ").trim());
    }
    log.info(
        "Sign-in SMS sent to {} via Text.lk (uid {})",
        masked(recipient),
        reply.path("data").path("uid").asText("?"));
  }

  private JsonNode readQuietly(String body) {
    try {
      return json.readTree(body == null || body.isBlank() ? "{}" : body);
    } catch (IOException ex) {
      return json.createObjectNode();
    }
  }

  /** 94771234567 → •••••••••567: enough to match a support call, not enough to identify. */
  private static String masked(String number) {
    return number.length() <= 3 ? "•••" : "•".repeat(number.length() - 3) + number.substring(number.length() - 3);
  }

  /** The SMS could not be sent; the sign-in request answers with its own "try again" error. */
  public static class SmsSendException extends RuntimeException {
    public SmsSendException(String message) {
      super(message);
    }
  }
}
