package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiSmsFormatCandidate;
import com.moneyreserve.domain.BiSmsFormatSample;
import com.moneyreserve.domain.BiSmsSenderAllowlist;
import com.moneyreserve.domain.BiSmsVocabularyWord;
import com.moneyreserve.domain.BiUnmatchedSmsStat;
import com.moneyreserve.domain.FormatCandidateStatus;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiSmsFormatCandidateRepository;
import com.moneyreserve.repository.BiSmsFormatSampleRepository;
import com.moneyreserve.repository.BiSmsSenderAllowlistRepository;
import com.moneyreserve.repository.BiSmsVocabularyWordRepository;
import com.moneyreserve.repository.BiUnmatchedSmsStatRepository;
import com.moneyreserve.service.impl.FormatDiscoveryMaintenance;
import com.moneyreserve.service.impl.UserHasher;
import com.moneyreserve.service.ingest.SmsVocabularyFixture;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * SMS format discovery end to end: a handset's counts and skeletons in, the admin's coverage
 * report and format candidates out, listing from the first customer, withdrawal and erasure, retention, and
 * a format becoming RESOLVED once a live template reads it.
 *
 * <p>Every test uses its own sender id, so the tests do not see each other's formats.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("SMS format discovery")
class FormatDiscoveryIT {

  private static final String STATS = "/api/insights/formats/unmatched-stats";
  private static final String SAMPLES = "/api/insights/formats/samples";
  private static final String CONSENT = "/api/insights/consent";
  private static final String CANDIDATES = AdminTestSupport.CONFIG + "/format-candidates";
  private static final String GRANT =
      "{\"consentVersion\":\"1.0\",\"policyHash\":\"fmt\",\"channel\":\"ANDROID\"}";
  private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

  /** Golden row 1 (Commercial Bank card purchase), as a phone would send it. */
  private static final String COMBANK_SMS =
      "Dear Cardholder, Purchase at KEELLS SUPER NUGEGODA for LKR 2,450.00 on 06/10/26 06:22 PM"
          + " has been authorised on your debit card ending #4521.";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiSmsVocabularyWordRepository vocabulary;
  @Autowired private BiSmsFormatCandidateRepository candidates;
  @Autowired private BiSmsFormatSampleRepository samples;
  @Autowired private BiUnmatchedSmsStatRepository stats;
  @Autowired private BiSmsSenderAllowlistRepository allowlist;
  @Autowired private BiBankParserTemplateRepository templates;
  @Autowired private FormatDiscoveryMaintenance maintenance;
  @Autowired private UserHasher userHasher;

  private final ObjectMapper json = new ObjectMapper();

  @BeforeEach
  void seedVocabulary() {
    // The H2 schema comes from the entities, without Flyway, so the seed is loaded from the
    // migration file itself: the vocabulary under test is exactly what an environment gets.
    if (vocabulary.count() == 0) {
      List<BiSmsVocabularyWord> rows = new ArrayList<>();
      SmsVocabularyFixture.byId()
          .forEach(
              (id, word) ->
                  rows.add(
                      BiSmsVocabularyWord.builder()
                          .id(id)
                          .word(word)
                          .source("BANKING")
                          .isActive(true)
                          .addedInVersion(1)
                          .build()));
      vocabulary.saveAll(rows);
    }
  }

  // ---------------------------------------------------------------------------------
  // Phase 1
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("counts are recorded per device and day, a retry replaces, bad items are rejected")
  void countsAreRecordedAndReplaced() throws Exception {
    UUID user = consentedUser();
    String sender = uniqueSender();
    LocalDate yesterday = LocalDate.now(ZONE).minusDays(1);

    JsonNode first =
        post(STATS, user, statsBody("dev-" + sender, stat(sender, yesterday, 3), stat("0771234567", yesterday, 1), stat(sender, yesterday.minusDays(30), 2)));
    assertEquals(1, first.path("recorded").asInt(), first.toString());
    assertEquals(2, first.path("rejected").asInt(), first.toString());

    post(STATS, user, statsBody("dev-" + sender, stat(sender, yesterday, 5)));
    List<BiUnmatchedSmsStat> rows =
        stats.findAll().stream().filter(r -> r.getSenderId().equals(sender)).toList();
    assertEquals(1, rows.size(), "a retry must replace, not add");
    assertEquals(5, rows.get(0).getMsgCount());
    assertTrue(stats.findAll().stream().noneMatch(r -> r.getSenderId().startsWith("077")));

    // A sender not on the allowlist is hidden until 5 devices have seen it.
    post(STATS, user, statsBody("dev2-" + sender, stat(sender, yesterday, 2)));
    assertFalse(coverageHas(sender), "an unknown sender seen by 2 devices must not be listed");
    for (int d = 3; d <= 5; d++) {
      post(STATS, user, statsBody("dev" + d + "-" + sender, stat(sender, yesterday, 1)));
    }
    JsonNode coverage =
        AdminTestSupport.data(admin().call(HttpMethod.GET, AdminTestSupport.CONFIG + "/sender-coverage?days=7", null));
    JsonNode row = find(coverage, "senderId", sender);
    assertEquals(10, row.path("unmatched").asInt(), coverage.toString());
    assertEquals(5, row.path("devices").asInt());
    assertFalse(row.path("onAllowlist").asBoolean());
  }

  @Test
  @DisplayName("senders that look like a person or a code are rejected")
  void personLikeSendersRejected() throws Exception {
    UUID user = consentedUser();
    LocalDate day = LocalDate.now(ZONE);
    JsonNode r =
        post(
            STATS,
            user,
            statsBody(
                "dev-p",
                stat("077-123-4567", day, 1),
                stat("+94.771234567", day, 1),
                stat("OTP 482913", day, 1),
                stat("PIN AB", day, 1),
                stat("8888", day, 1)));
    assertEquals(4, r.path("rejected").asInt(), r.toString());
    assertEquals(1, r.path("recorded").asInt(), "a short code is a bank sender");
  }

  @Test
  @DisplayName("without consent a handset cannot report")
  void consentIsRequired() {
    UUID user = UUID.randomUUID();
    ResponseEntity<String> r =
        exchange(STATS, HttpMethod.POST, user, statsBody("dev-x", stat("NOCONSENT", LocalDate.now(ZONE), 1)));
    assertEquals(403, r.getStatusCode().value(), r.getBody());
  }

  // ---------------------------------------------------------------------------------
  // Phase 2
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a format reaches the admin from the first customer; one customer counts once")
  void firstCustomerListed() throws Exception {
    String sender = uniqueSender();
    List<String> tokens = combankTokens();
    UUID first = consentedUser();
    JsonNode r = post(SAMPLES, first, samplesBody(sender, tokens));
    assertEquals("ACCEPTED", r.path("results").get(0).path("status").asText(), r.toString());

    List<JsonNode> listed = listFor(sender);
    assertEquals(1, listed.size());
    JsonNode item = listed.get(0);
    assertEquals(1, item.path("distinctUsers").asInt());
    assertEquals(
        "Dear Cardholder, Purchase at {W} for LKR {AMT} on {DATE:dd/MM/yy} {TIME:hh:mm a} has been"
            + " authorised on your debit card ending #{N}.",
        item.path("skeleton").asText());
    assertFalse(item.toString().contains("KEELLS"));
    assertFalse(item.toString().contains("2,450"));

    // The same customer again is one customer; a second customer makes two.
    JsonNode again = post(SAMPLES, first, samplesBody(sender, tokens));
    assertEquals("DUPLICATE", again.path("results").get(0).path("status").asText());
    assertEquals(1, candidateOf(sender).getDistinctUsers());
    UUID second = consentedUser();
    post(SAMPLES, second, samplesBody(sender, tokens));
    assertEquals(2, listFor(sender).get(0).path("distinctUsers").asInt());

    // Withdrawal takes that customer's sample with it.
    ResponseEntity<String> withdrawn = exchange(CONSENT, HttpMethod.DELETE, second, null);
    assertTrue(withdrawn.getStatusCode().is2xxSuccessful(), withdrawn.getBody());
    assertEquals(1, candidateOf(sender).getDistinctUsers());
    assertEquals(1, listFor(sender).size());
  }

  @Test
  @DisplayName("malformed or content-bearing tokens are rejected per sample, with codes only")
  void badSamplesAreRejected() throws Exception {
    UUID user = consentedUser();
    String sender = uniqueSender();
    List<String> withName = new ArrayList<>(combankTokens());
    withName.set(5, "KEELLS");
    List<String> noAmount = new ArrayList<>(combankTokens());
    noAmount.removeIf(t -> t.startsWith("AMT"));
    String body =
        "{\"deviceId\":\"dev-1\",\"platform\":\"ANDROID\",\"vocabularyVersion\":1,\"samples\":["
            + sample(sender, withName)
            + ","
            + sample(sender, noAmount)
            + ","
            + sample("0771234567", combankTokens())
            + "]}";
    JsonNode r = post(SAMPLES, user, body);
    assertEquals(3, r.path("rejected").asInt(), r.toString());
    assertEquals("MR.FMT.E.003", r.path("results").get(0).path("error").asText());
    assertEquals("MR.FMT.E.004", r.path("results").get(1).path("error").asText());
    assertEquals("MR.FMT.E.003", r.path("results").get(2).path("error").asText());
    assertFalse(r.toString().contains("KEELLS"), "the response must not echo what was sent");
    assertTrue(candidates.findAll().stream().noneMatch(c -> c.getSenderId().equals(sender)));
  }

  @Test
  @DisplayName("a vocabulary version the server never issued is refused")
  void unknownVocabularyVersion() throws Exception {
    String body =
        "{\"deviceId\":\"dev-1\",\"platform\":\"ANDROID\",\"vocabularyVersion\":999,\"samples\":["
            + sample(uniqueSender(), combankTokens())
            + "]}";
    JsonNode r = post(SAMPLES, consentedUser(), body);
    assertEquals("REJECTED", r.path("results").get(0).path("status").asText());
  }

  @Test
  @DisplayName("reference sync serves the vocabulary only to a phone that asks for it")
  void vocabularyInSync() throws Exception {
    UUID user = consentedUser();
    JsonNode without =
        json.readTree(exchange("/api/insights/reference/sync?deviceId=d1", HttpMethod.GET, user, null).getBody());
    assertTrue(without.path("data").path("vocabulary").isMissingNode(), without.toString());
    JsonNode with =
        json.readTree(
            exchange("/api/insights/reference/sync?deviceId=d1&vocabularyVersion=0", HttpMethod.GET, user, null)
                .getBody());
    JsonNode v = with.path("data").path("vocabulary");
    assertEquals(1, v.path("version").asInt(), with.toString());
    assertEquals(SmsVocabularyFixture.byId().size(), v.path("words").size());
    JsonNode current =
        json.readTree(
            exchange("/api/insights/reference/sync?deviceId=d1&vocabularyVersion=1", HttpMethod.GET, user, null)
                .getBody());
    assertTrue(current.path("data").path("vocabulary").isMissingNode());
  }

  // ---------------------------------------------------------------------------------
  // Admin actions
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("draft-template returns a synthetic sample and the wizard's analysis; reject closes it")
  void draftAndReject() throws Exception {
    String sender = uniqueSender();
    UUID id = visibleCandidate(sender);

    ResponseEntity<String> drafted =
        admin().call(
            HttpMethod.POST, CANDIDATES + "/" + id + "/draft-template", "{\"bankCode\":\"" + activeBank() + "\"}");
    assertEquals(200, drafted.getStatusCode().value(), drafted.getBody());
    JsonNode draft = AdminTestSupport.data(drafted);
    assertEquals(
        "Dear Cardholder, Purchase at Sample Text for LKR 1,234.56 on 05/03/26 02:35 PM has been"
            + " authorised on your debit card ending #1234.",
        draft.path("sample").asText());
    assertTrue(draft.path("analysis").path("suggestions").size() > 0, draft.toString());

    ResponseEntity<String> bad =
        admin().call(HttpMethod.POST, CANDIDATES + "/" + id + "/reject", "{\"reason\":\"because\"}");
    assertEquals(400, bad.getStatusCode().value());
    ResponseEntity<String> rejected =
        admin().call(HttpMethod.POST, CANDIDATES + "/" + id + "/reject", "{\"reason\":\"PROMOTION\"}");
    assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
    assertEquals("REJECTED", AdminTestSupport.data(rejected).path("status").asText());
    assertEquals(0, listFor(sender).size(), "a rejected format leaves the OPEN list");
    assertEquals(
        400,
        admin().call(HttpMethod.POST, CANDIDATES + "/" + id + "/reject", "{\"reason\":\"OTHER\"}")
            .getStatusCode()
            .value());
  }

  @Test
  @DisplayName("a format a live template reads is shown as covered, then RESOLVED by maintenance")
  void coveredFormatsResolve() throws Exception {
    String sender = uniqueSender();
    UUID id = visibleCandidate(sender);
    BiSmsSenderAllowlist allowed =
        allowlist.save(
            BiSmsSenderAllowlist.builder()
                .id(UUID.randomUUID())
                .bankCode("7056")
                .senderId(sender)
                .countryCode("LK")
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build());
    BiBankParserTemplate template =
        templates.save(
            BiBankParserTemplate.builder()
                .id(UUID.randomUUID())
                .bankCode("7056")
                .messageType("FMT_IT_" + sender)
                .pattern(seededTemplatePattern("b1e70000-0000-4000-a000-000000000001"))
                .fieldMap("{}")
                .templateVersion(1)
                .isActive(true)
                .build());
    try {
      JsonNode item = listFor(sender).get(0);
      // Our template, or a seeded Commercial Bank template that reads the same format (with
      // Flyway's seed present): either way a live template of the sender's bank.
      UUID covering = UUID.fromString(item.path("coveredByTemplateId").asText());
      assertEquals("7056", templates.findById(covering).orElseThrow().getBankCode(), item.toString());
      assertEquals("7056", item.path("bankCode").asText());

      FormatDiscoveryMaintenance.Outcome outcome = maintenance.run(OffsetDateTime.now(ZONE));
      assertTrue(outcome.formatsResolved() >= 1);
      BiSmsFormatCandidate c = candidates.findById(id).orElseThrow();
      assertEquals(FormatCandidateStatus.RESOLVED, c.getStatus());
      assertEquals(covering, c.getTemplateId());
    } finally {
      templates.delete(template);
      allowlist.delete(allowed);
    }
  }

  @Test
  @DisplayName("retention expires samples after their lifetime and removes formats left empty")
  void retention() throws Exception {
    String sender = uniqueSender();
    UUID id = visibleCandidate(sender);
    maintenance.run(OffsetDateTime.now(ZONE).plusDays(91));
    assertFalse(candidates.findById(id).isPresent());
    assertEquals(0, samples.countByCandidateId(id));
  }

  @Test
  @DisplayName("a sample that lands after a withdrawal is swept by the daily pass")
  void withdrawalRaceIsSwept() throws Exception {
    String sender = uniqueSender();
    UUID user = consentedUser();
    post(SAMPLES, user, samplesBody(sender, combankTokens()));
    UUID candidate = candidateOf(sender).getId();
    assertTrue(exchange(CONSENT, HttpMethod.DELETE, user, null).getStatusCode().is2xxSuccessful());
    assertEquals(0, samples.countByCandidateId(candidate));
    // The race: an upload that passed its consent check commits after the withdrawal.
    samples.save(
        BiSmsFormatSample.builder()
            .candidateId(candidate)
            .userHash(userHasher.hash(user))
            .vocabularyVersion(1)
            .receivedAt(OffsetDateTime.now(ZONE))
            .ttlExpiresAt(OffsetDateTime.now(ZONE).plusDays(90))
            .build());
    FormatDiscoveryMaintenance.Outcome outcome = maintenance.run(OffsetDateTime.now(ZONE));
    assertTrue(outcome.samplesForgotten() >= 1, outcome.toString());
    assertEquals(0, samples.countByCandidateId(candidate));
  }

  @Test
  @DisplayName("erasure removes the customer's samples and says so")
  void erasure() throws Exception {
    String sender = uniqueSender();
    UUID user = consentedUser();
    post(SAMPLES, user, samplesBody(sender, combankTokens()));
    ResponseEntity<String> erased = exchange("/api/insights/data", HttpMethod.DELETE, user, null);
    assertEquals(200, erased.getStatusCode().value(), erased.getBody());
    assertEquals(1, json.readTree(erased.getBody()).path("data").path("removedByTable").path("formatSamples").asInt());
    assertEquals(0, candidateOf(sender).getDistinctUsers());
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private boolean coverageHas(String sender) throws Exception {
    JsonNode coverage =
        AdminTestSupport.data(admin().call(HttpMethod.GET, AdminTestSupport.CONFIG + "/sender-coverage?days=7", null));
    for (JsonNode n : coverage) {
      if (sender.equals(n.path("senderId").asText())) {
        return true;
      }
    }
    return false;
  }

  /** An active bank from the registry, rather than a hard-coded code another test may retire. */
  private String activeBank() throws Exception {
    for (JsonNode b : AdminTestSupport.data(admin().config(HttpMethod.GET, "/banks", null))) {
      if (b.path("active").asBoolean()) {
        return b.path("bankCode").asText();
      }
    }
    throw new AssertionError("no active bank");
  }

  private UUID visibleCandidate(String sender) throws Exception {
    for (int i = 0; i < 5; i++) {
      post(SAMPLES, consentedUser(), samplesBody(sender, combankTokens()));
    }
    List<JsonNode> listed = listFor(sender);
    assertEquals(1, listed.size());
    return UUID.fromString(listed.get(0).path("id").asText());
  }

  private static List<String> combankTokens() {
    // Built by the same rules as a phone, via the golden file (SkeletonGoldenTest holds the file
    // to the reference builder).
    String golden = SmsVocabularyFixture.resource("/format-discovery/skeleton-golden.tsv");
    for (String line : golden.split("\n")) {
      String[] p = line.split("\t");
      if (p.length == 3 && p[1].equals(COMBANK_SMS)) {
        return List.of(p[2].split("\\|"));
      }
    }
    throw new IllegalStateException("golden row for the ComBank purchase is missing");
  }

  private static String seededTemplatePattern(String id) {
    Matcher m =
        Pattern.compile(
                "'" + Pattern.quote(id) + "'::uuid, '[^']+', '[^']+',\\s*\\$re\\$(.*?)\\$re\\$",
                Pattern.DOTALL)
            .matcher(SmsVocabularyFixture.resource("/db/migration/common/V1.3.0__bank_sms_parser_seed.sql"));
    assertTrue(m.find());
    return m.group(1);
  }

  private BiSmsFormatCandidate candidateOf(String sender) {
    return candidates.findAll().stream()
        .filter(c -> c.getSenderId().equals(sender))
        .findFirst()
        .orElseThrow();
  }

  private List<JsonNode> listFor(String sender) throws Exception {
    ResponseEntity<String> r = admin().call(HttpMethod.GET, CANDIDATES + "?senderId=" + sender, null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    List<JsonNode> out = new ArrayList<>();
    AdminTestSupport.data(r).path("items").forEach(out::add);
    return out;
  }

  private static JsonNode find(JsonNode array, String field, String value) {
    for (JsonNode n : array) {
      if (value.equals(n.path(field).asText())) {
        return n;
      }
    }
    throw new AssertionError(value + " not found in " + array);
  }

  private AdminTestSupport admin() {
    return new AdminTestSupport(rest, UUID.randomUUID());
  }

  /** Letters only: a sender with many digits is refused as person-like. */
  private static String uniqueSender() {
    StringBuilder out = new StringBuilder("IT");
    java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
    for (int i = 0; i < 10; i++) {
      out.append((char) ('A' + r.nextInt(26)));
    }
    return out.toString();
  }

  private static String stat(String sender, LocalDate day, int count) {
    return "{\"senderId\":\"" + sender + "\",\"day\":\"" + day + "\",\"count\":" + count + "}";
  }

  private static String statsBody(String device, String... items) {
    return "{\"deviceId\":\"" + device + "\",\"platform\":\"ANDROID\",\"stats\":[" + String.join(",", items) + "]}";
  }

  private String sample(String sender, List<String> tokens) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "clientRef", UUID.randomUUID().toString().replace("-", ""),
            "senderId", sender,
            "tokens", tokens));
  }

  private String samplesBody(String sender, List<String> tokens) throws Exception {
    return "{\"deviceId\":\"dev-1\",\"platform\":\"ANDROID\",\"vocabularyVersion\":1,\"samples\":["
        + sample(sender, tokens)
        + "]}";
  }

  private UUID consentedUser() {
    UUID user = UUID.randomUUID();
    ResponseEntity<String> granted = exchange(CONSENT, HttpMethod.POST, user, GRANT);
    assertTrue(granted.getStatusCode().is2xxSuccessful(), granted.getBody());
    return user;
  }

  private JsonNode post(String path, UUID user, String body) throws Exception {
    ResponseEntity<String> r = exchange(path, HttpMethod.POST, user, body);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return json.readTree(r.getBody()).path("data");
  }

  private ResponseEntity<String> exchange(String path, HttpMethod method, UUID user, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(user));
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }
}
