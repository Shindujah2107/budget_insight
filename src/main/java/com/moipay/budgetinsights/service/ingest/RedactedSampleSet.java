package com.moipay.budgetinsights.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The redacted real bank SMS the templates are checked against ({@code
 * bank-sms/redacted-samples.json}): {@code samples} each belong to exactly one bank and message
 * type; {@code mustNotMatch} are OTP, marketing, declined and informational messages no template
 * may ever read.
 *
 * <p>Already redacted at source (names, phones, references, account digits replaced). Read once
 * at start-up; never written, logged or returned by any endpoint.
 */
@Slf4j
@Component
public class RedactedSampleSet {

  static final String RESOURCE = "/bank-sms/redacted-samples.json";

  /** One redacted message and the template it belongs to. */
  public record Sample(String bankCode, String messageType, String sms) {}

  private final List<Sample> samples = new ArrayList<>();
  private final List<String> mustNotMatch = new ArrayList<>();

  public RedactedSampleSet(ObjectMapper objectMapper) {
    try (InputStream in = RedactedSampleSet.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        log.error("{} is not on the classpath; template regression checks are skipped", RESOURCE);
        return;
      }
      JsonNode root = objectMapper.readTree(in);
      for (JsonNode s : root.path("samples")) {
        samples.add(
            new Sample(
                s.path("bankCode").asText(), s.path("messageType").asText(), s.path("sms").asText()));
      }
      for (JsonNode n : root.path("mustNotMatch")) {
        mustNotMatch.add(n.path("sms").asText());
      }
      log.info(
          "Template regression sample set loaded: {} samples, {} must-not-match",
          samples.size(),
          mustNotMatch.size());
    } catch (IOException ex) {
      throw new IllegalStateException(RESOURCE + " could not be read", ex);
    }
  }

  public List<Sample> samples() {
    return samples;
  }

  public List<Sample> samplesFor(String bankCode) {
    return samples.stream().filter(s -> s.bankCode().equals(bankCode)).toList();
  }

  public List<String> mustNotMatch() {
    return mustNotMatch;
  }
}
