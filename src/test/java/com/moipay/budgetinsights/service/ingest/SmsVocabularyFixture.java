package com.moipay.budgetinsights.service.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The vocabulary exactly as migration V1.13.0 seeds it, read from the migration file itself so
 * the tests can never drift from what an environment actually gets.
 */
public final class SmsVocabularyFixture {

  static final String MIGRATION = "/db/migration/common/V1.13.0__sms_format_discovery.sql";

  private static final Pattern ROW =
      Pattern.compile("\\((\\d+), '([A-Z]+)', '(FUNCTION|BANKING|BANK|TEMPLATE)', (\\d+)\\)");

  private static Map<Integer, String> byId;

  private SmsVocabularyFixture() {}

  /** id → word, in migration order. */
  public static synchronized Map<Integer, String> byId() {
    if (byId == null) {
      Map<Integer, String> out = new LinkedHashMap<>();
      Matcher m = ROW.matcher(migration());
      while (m.find()) {
        out.put(Integer.parseInt(m.group(1)), m.group(2));
      }
      byId = Collections.unmodifiableMap(out);
    }
    return byId;
  }

  /** word → id. */
  public static Map<String, Integer> byWord() {
    Map<String, Integer> out = new LinkedHashMap<>();
    byId().forEach((id, word) -> out.put(word, id));
    return out;
  }

  static String migration() {
    return resource(MIGRATION);
  }

  public static String resource(String path) {
    try (InputStream in = SmsVocabularyFixture.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
