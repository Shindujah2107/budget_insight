package com.moneyreserve.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bank SMS seed (V1.3.0) checked against real, redacted bank messages.
 *
 * <p>Flyway is off on the test profile, so no other test ever reads this migration. This one reads
 * the SQL file itself, pulls out every template, and applies each one the way a device must: match
 * the pattern, then read the fields through {@code field_map}. It then checks the result against
 * what the server will accept and against values worked out by hand for each sample.
 *
 * <p>What it guards:
 *
 * <ul>
 *   <li>every real sample is matched by <b>exactly one</b> template, across all banks, so templates
 *       stay mutually exclusive as rows are added;
 *   <li>OTP, declined-card and excluded-format messages match <b>none</b>;
 *   <li>every template states a direction, so no credit falls back to the server's DEBIT default;
 *   <li>a tail is either exactly four digits or absent &mdash; never padded, never truncated &mdash;
 *       which is what {@code ck_bi_account_masked_tail} and {@code IngestMessage} accept;
 *   <li>no masked account number reaches {@code merchant} &mdash; the tail rule would mean
 *       nothing if the digits it refuses arrived through the free-text field instead;
 *   <li>a template with no date in its SMS falls back to the received date.
 * </ul>
 */
@DisplayName("Bank SMS seed templates (V1.3.0) against real redacted messages")
class BankSmsTemplateSeedTest {

  private static final String MIGRATION = "/db/migration/common/V1.3.0__bank_sms_parser_seed.sql";
  private static final String SAMPLES = "/bank-sms/redacted-samples.json";
  private static final LocalDate RECEIVED = LocalDate.of(2026, 9, 21);

  private static final Pattern TEMPLATE_ROW =
      Pattern.compile(
          "\\('([0-9a-f-]{36})'::uuid, '([^']+)', '([^']+)',\\s*\\$re\\$(.*?)\\$re\\$,"
              + "\\s*\\$fm\\$(.*?)\\$fm\\$::jsonb,\\s*(\\d+), (true|false)\\)",
          Pattern.DOTALL);
  private static final Pattern SENDER_ROW =
      Pattern.compile("\\('([0-9a-f-]{36})'::uuid, '([^']+)', '([^']+)', 'LK', (true|false)\\)");
  private static final Pattern GROUP_NAME = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

  // What the server accepts (PayloadNormaliser, IngestMessage).
  private static final Pattern BANK_CODE = Pattern.compile("^[A-Z0-9]{2,16}$");
  private static final Pattern MESSAGE_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,30}$");
  private static final Pattern MASKED_TAIL = Pattern.compile("^[0-9]{4}$");
  /**
   * A masked account or card number in the shapes the samples use: {@code 01902XXXXX45},
   * {@code ****7495}. Long plain digit runs are not included: merchants carry phone numbers.
   */
  private static final Pattern ACCOUNT_LIKE = Pattern.compile("[0-9]*[X*]{2,}[0-9]{2,}");

  /** The five banks with SAFE TO SEED templates. The other six of the 11-bank scope have none. */
  private static final Set<String> SEEDED_BANKS = Set.of("7056", "7083", "7010", "7287", "7463");

  /** Exactly these senders, spelled exactly like this. */
  private static final Set<String> SENDERS_EXPECTED =
      Set.of("COMBANK", "ComBank_Q+", "HNB", "BOC", "SEYLAN", "AMANABANK");

  /** OTP-only, marketing, unverified-format and out-of-scope senders. */
  private static final Set<String> NEVER_ALLOWLISTED =
      Set.of("BOCONLINE", "HSBC SL", "HSBCPremier", "HSBC Alerts", "PeoplesBank", "NDB ALERT");

  private static final ObjectMapper JSON = new ObjectMapper();

  private record Template(
      String id, String bank, String type, Pattern pattern, JsonNode map, int version,
      boolean active) {}

  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("the migration has the expected rows, all version 1, keys unique and server-valid")
  void rowsAreWellFormed() throws IOException {
    String sql = migration();
    List<Template> templates = templates(sql);
    Map<String, String> senders = senders(sql);

    assertEquals(11, templates.size(), "template count");
    assertEquals(6, senders.size(), "sender count");
    assertEquals(SENDERS_EXPECTED, senders.keySet(), "sender ids must be exactly the verified ones");

    Set<String> keys = new HashSet<>();
    Set<String> ids = new HashSet<>();
    for (Template t : templates) {
      assertTrue(ids.add(t.id()), "duplicate id " + t.id());
      assertTrue(keys.add(t.bank() + "|" + t.type() + "|" + t.version()),
          "ux_bi_tpl_bank_type_version collision: " + t.bank() + " " + t.type());
      assertTrue(SEEDED_BANKS.contains(t.bank()), "unexpected bank " + t.bank());
      assertTrue(BANK_CODE.matcher(t.bank()).matches(), "bank code " + t.bank());
      assertTrue(MESSAGE_TYPE.matcher(t.type()).matches(), "message type " + t.type());
      assertEquals(1, t.version(), "first version of each grammar: " + t.type());
      assertTrue(t.active(), "seeded active for DEV testing: " + t.type());
    }
    for (Map.Entry<String, String> s : senders.entrySet()) {
      assertTrue(SEEDED_BANKS.contains(s.getValue()), "sender for unexpected bank: " + s.getKey());
      assertFalse(NEVER_ALLOWLISTED.contains(s.getKey()), "OTP/marketing sender seeded: " + s.getKey());
      assertTrue(s.getKey().length() <= 32, "sender_id too long: " + s.getKey());
    }
    // Every bank that has a template has a sender, and the reverse.
    Set<String> templateBanks = new HashSet<>();
    templates.forEach(t -> templateBanks.add(t.bank()));
    assertEquals(templateBanks, new HashSet<>(senders.values()));
  }

  @Test
  @DisplayName("every pattern is anchored, flag-free, and every field_map is complete")
  void templatesAreSelfConsistent() throws IOException {
    for (Template t : templates(migration())) {
      String p = t.pattern().pattern();
      String name = t.bank() + " " + t.type();
      assertTrue(p.startsWith("^"), name + ": pattern must be anchored");
      assertFalse(p.contains("(?i") || p.contains("(?s") || p.contains("(?m"), name + ": inline flag");
      assertFalse(p.contains("(?<=") || p.contains("(?<!"), name + ": lookbehind is not portable");

      Set<String> groups = new HashSet<>();
      Matcher g = GROUP_NAME.matcher(p);
      while (g.find()) {
        groups.add(g.group(1));
      }
      JsonNode m = t.map();
      assertEquals(1, m.path("fieldMapVersion").asInt(), name);
      assertTrue(Set.of("CARD", "BANK").contains(m.path("instrumentType").asText()), name);
      assertTrue(groups.contains(m.path("amount").path("group").asText()), name + ": amount group");

      JsonNode dir = m.path("direction");
      if (dir.has("value")) {
        assertTrue(Set.of("DEBIT", "CREDIT").contains(dir.get("value").asText()), name);
      } else {
        assertTrue(groups.contains(dir.path("group").asText()), name + ": direction group");
        assertTrue(dir.path("map").size() > 0, name + ": direction map");
        dir.path("map").forEach(v ->
            assertTrue(Set.of("DEBIT", "CREDIT").contains(v.asText()), name + ": direction value"));
      }

      JsonNode date = m.path("txnDate");
      if (date.has("source")) {
        assertEquals("SMS_RECEIVED_DATE", date.get("source").asText(), name);
        assertFalse(groups.contains("date"), name + ": has a date group but falls back");
      } else {
        assertTrue(groups.contains(date.path("group").asText()), name + ": date group");
        DateTimeFormatter.ofPattern(date.path("format").asText());
      }

      JsonNode tail = m.path("maskedTail");
      if (tail.has("group")) {
        assertTrue(groups.contains(tail.get("group").asText()), name + ": tail group");
        assertEquals(4, tail.path("digits").asInt(), name);
      } else {
        assertTrue(Set.of("ABSENT", "NOT_IN_SMS").contains(tail.path("policy").asText()), name);
        assertFalse(groups.contains("tail"), name + ": captures a tail it declares absent");
      }
      JsonNode merchant = m.path("merchant");
      if (!merchant.isNull()) {
        assertTrue(groups.contains(merchant.path("group").asText()), name + ": merchant group");
      }
    }
  }

  @Test
  @DisplayName("each real sample matches exactly one template and yields the hand-checked fields")
  void samplesParseToTheExpectedFields() throws IOException {
    List<Template> templates = templates(migration());
    JsonNode samples = samples().path("samples");
    assertTrue(samples.size() >= 42, "sample set has been thinned out");

    List<String> failures = new ArrayList<>();
    Set<String> exercised = new HashSet<>();
    for (JsonNode s : samples) {
      String sms = s.path("sms").asText();
      String want = s.path("bankCode").asText() + " " + s.path("messageType").asText();
      List<Template> hits = matching(templates, sms);
      if (hits.size() != 1 || !want.equals(hits.get(0).bank() + " " + hits.get(0).type())) {
        failures.add(want + " matched " + hits.stream().map(h -> h.bank() + " " + h.type()).toList());
        continue;
      }
      exercised.add(want);
      Map<String, String> got = interpret(hits.get(0), sms);
      s.path("expected").fields().forEachRemaining(e -> {
        String expected = e.getValue().isNull() ? null : e.getValue().asText();
        if (!java.util.Objects.equals(expected, got.get(e.getKey()))) {
          failures.add(want + " " + e.getKey() + ": expected " + expected + ", got " + got.get(e.getKey()));
        }
      });
      String merchant = got.get("merchant");
      if (merchant != null && ACCOUNT_LIKE.matcher(merchant).find()) {
        failures.add(want + ": merchant carries an account number: " + merchant);
      }
      String t = got.get("maskedTail");
      if (t != null && !MASKED_TAIL.matcher(t).matches()) {
        failures.add(want + ": tail " + t + " would be rejected by ck_bi_account_masked_tail");
      }
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));

    Set<String> seeded = new HashSet<>();
    templates.forEach(t -> seeded.add(t.bank() + " " + t.type()));
    seeded.removeAll(exercised);
    assertTrue(seeded.isEmpty(), "templates with no real sample behind them: " + seeded);
  }

  @Test
  @DisplayName("OTP, marketing, declined, rejected, pending, informational and unseeded messages match nothing")
  void nonTransactionsMatchNothing() throws IOException {
    List<Template> templates = templates(migration());
    JsonNode negatives = samples().path("mustNotMatch");
    assertTrue(negatives.size() >= 41, "must-not-match set has been thinned out");
    for (JsonNode n : negatives) {
      List<Template> hits = matching(templates, n.path("sms").asText());
      assertTrue(hits.isEmpty(), n.path("why").asText() + " matched " + hits.stream()
          .map(h -> h.bank() + " " + h.type()).toList());
    }
  }

  @Test
  @DisplayName("the short-tail shapes send no tail rather than a padded or truncated one")
  void shortTailShapesSendNoTail() throws IOException {
    Set<String> absent = new HashSet<>();
    for (Template t : templates(migration())) {
      if ("ABSENT".equals(t.map().path("maskedTail").path("policy").asText())) {
        absent.add(t.bank() + " " + t.type());
      }
    }
    assertEquals(Set.of("7010 ACCOUNT_TXN", "7083 ACCOUNT_TXN", "7083 ACCOUNT_CHARGE"), absent);
  }

  @Test
  @DisplayName("HNB charge/refund: both line layouts parse, debit ed is DEBIT and credit ed is CREDIT")
  void hnbChargeAndRefund() throws IOException {
    Template charge = templates(migration()).stream()
        .filter(t -> t.bank().equals("7083") && t.type().equals("ACCOUNT_CHARGE"))
        .findFirst().orElseThrow();
    JsonNode dir = charge.map().path("direction");
    assertFalse(dir.has("value"), "the charge template must not be fixed to one direction");
    assertEquals("DEBIT", dir.path("map").path("debited").asText());
    assertEquals("CREDIT", dir.path("map").path("credited").asText());

    String base = "A Transaction for LKR 25.00 has been debit ed to Ac No:01902XXXXX33 on 05/09/25 05:31:18";
    String tail = "Remarks :HNB Alert Charges.Bal: LKR 6,572.54";
    assertEquals("DEBIT", interpret(charge, base + " . " + tail).get("direction"), "space before Remarks");
    assertEquals("DEBIT", interpret(charge, base + " .\n" + tail).get("direction"), "line break before Remarks");

    Map<String, String> refund = interpret(charge,
        "A Transaction for LKR 410.90 has been credit ed to Ac No:01902XXXXX45 on 24/06/26 13:54:03 . "
            + "Remarks :FX MARKUP_37213.91 06/14.Bal: LKR 624.91");
    assertEquals("CREDIT", refund.get("direction"));
    assertEquals("410.90", refund.get("amount"));
    assertEquals(null, refund.get("maskedTail"));
    assertFalse(ACCOUNT_LIKE.matcher(refund.get("merchant")).find(), "account digits in merchant");
  }

  // ---------------------------------------------------------------------------------------------
  // The reference reading of field_map: what a device is expected to do with a match.

  private static Map<String, String> interpret(Template t, String sms) {
    Matcher m = t.pattern().matcher(sms);
    assertTrue(m.find());
    JsonNode fm = t.map();
    Map<String, String> out = new LinkedHashMap<>();

    String amount = m.group(fm.path("amount").path("group").asText()).replace(",", "");
    if (amount.startsWith(".")) {
      amount = "0" + amount;
    }
    out.put("amount", new BigDecimal(amount).setScale(2).toPlainString());

    JsonNode cur = fm.path("currency");
    out.put("currency", cur.has("value") ? cur.get("value").asText() : m.group(cur.path("group").asText()));

    JsonNode dir = fm.path("direction");
    out.put("direction", dir.has("value")
        ? dir.get("value").asText()
        : dir.path("map").path(m.group(dir.path("group").asText())
            .replaceAll("\\s+", "").toLowerCase(Locale.ROOT)).asText(null));

    JsonNode merchant = fm.path("merchant");
    if (!merchant.isNull()) {
      String v = m.group(merchant.path("group").asText());
      if ((v == null || v.isBlank()) && merchant.has("fallbackGroup")) {
        v = m.group(merchant.get("fallbackGroup").asText());
      }
      out.put("merchant", v == null ? null : v.trim().replaceAll("\\s+", " "));
    }

    JsonNode date = fm.path("txnDate");
    out.put("txnDate", date.has("source")
        ? RECEIVED.toString()
        : LocalDate.parse(m.group(date.path("group").asText()),
            DateTimeFormatter.ofPattern(date.path("format").asText(), Locale.ROOT)).toString());

    JsonNode tail = fm.path("maskedTail");
    out.put("maskedTail", tail.has("group") ? m.group(tail.get("group").asText()) : null);
    return out;
  }

  // ---------------------------------------------------------------------------------------------

  private static List<Template> matching(List<Template> templates, String sms) {
    return templates.stream().filter(t -> t.pattern().matcher(sms).find()).toList();
  }

  private static List<Template> templates(String sql) throws IOException {
    List<Template> out = new ArrayList<>();
    Matcher m = TEMPLATE_ROW.matcher(sql);
    while (m.find()) {
      out.add(new Template(m.group(1), m.group(2), m.group(3), Pattern.compile(m.group(4)),
          JSON.readTree(m.group(5)), Integer.parseInt(m.group(6)), Boolean.parseBoolean(m.group(7))));
    }
    return out;
  }

  private static Map<String, String> senders(String sql) {
    Map<String, String> out = new LinkedHashMap<>();
    Matcher m = SENDER_ROW.matcher(sql);
    while (m.find()) {
      assertTrue(Boolean.parseBoolean(m.group(4)), "sender seeded inactive: " + m.group(3));
      assertFalse(out.containsKey(m.group(3)), "duplicate sender " + m.group(3));
      out.put(m.group(3), m.group(2));
    }
    return out;
  }

  private static String migration() throws IOException {
    try (InputStream in = BankSmsTemplateSeedTest.class.getResourceAsStream(MIGRATION)) {
      assertNotNull(in, MIGRATION + " is not on the classpath");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static JsonNode samples() throws IOException {
    try (InputStream in = BankSmsTemplateSeedTest.class.getResourceAsStream(SAMPLES)) {
      if (in == null) {
        fail(SAMPLES + " is not on the classpath");
      }
      return JSON.readTree(in);
    }
  }
}
