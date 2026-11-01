package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiBudgetAlert;
import com.moneyreserve.domain.BillType;
import com.moneyreserve.domain.BudgetAlertType;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
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
 * Bills radar end to end: bills found in a customer's own payments, their own edits, bills added by
 * hand, the three bill alerts, and that nobody sees anyone else's bills.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Bills radar")
class BillsIT {

  private static final String BILLS = "/api/insights/bills";
  private static final String TRANSACTIONS = "/api/insights/transactions";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiBillerRepository billers;
  @Autowired private BiBudgetAlertRepository alerts;
  @Autowired private com.moneyreserve.repository.BiCategoryRepository categories;

  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

  @BeforeEach
  void seedReferenceData() {
    // The H2 schema has no Flyway seed: add the fallback category payments land in, and the one
    // biller these tests use.
    if (categories.findByCodeAndIsActiveTrue(com.moneyreserve.domain.BiCategory.OTHER_CODE).isEmpty()) {
      categories.save(
          com.moneyreserve.domain.BiCategory.builder()
              .id(UUID.randomUUID())
              .code(com.moneyreserve.domain.BiCategory.OTHER_CODE)
              .nameEn("Other")
              .iconKey("ic_other")
              .sortOrder((short) 17)
              .isActive(true)
              .createdAt(OffsetDateTime.now())
              .build());
    }
    if (billers.findByCode("NETFLIX").isEmpty()) {
      billers.save(
          BiBiller.builder()
              .id(UUID.randomUUID())
              .code("NETFLIX")
              .nameEn("Netflix")
              .billType(BillType.STREAMING)
              .matchTerms("NETFLIX")
              .iconKey("ic_subscriptions")
              .sortOrder((short) 30)
              .isActive(true)
              .createdAt(OffsetDateTime.now())
              .build());
    }
  }

  private HttpEntity<String> as(UUID user, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(user));
    if (body != null) {
      h.setContentType(MediaType.APPLICATION_JSON);
    }
    return new HttpEntity<>(body, h);
  }

  private void pay(UUID user, String merchant, String amount, LocalDate date) {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\""
            + merchant + "\",\"txnDate\":\"" + date + "\",\"clientRef\":\"" + UUID.randomUUID() + "\"}";
    ResponseEntity<String> r = rest.exchange(TRANSACTIONS, HttpMethod.POST, as(user, body), String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
  }

  private JsonNode data(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("data");
  }

  private JsonNode list(UUID user) throws Exception {
    ResponseEntity<String> r = rest.exchange(BILLS, HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private static JsonNode named(JsonNode list, String name) {
    for (JsonNode b : list.path("bills")) {
      if (name.equals(b.path("name").asText())) {
        return b;
      }
    }
    throw new AssertionError(name + " not in " + list);
  }

  private List<BudgetAlertType> alertTypes(String billId) {
    return alerts.findAll().stream()
        .filter(a -> billId.equals(String.valueOf(a.getBillId())))
        .map(BiBudgetAlert::getAlertType)
        .toList();
  }

  @Test
  @DisplayName("bills are found in the customer's own payments, with the monthly total")
  void foundFromPayments() throws Exception {
    UUID user = UUID.randomUUID();
    for (int back : new int[] {75, 45, 15}) {
      pay(user, "NETFLIX.COM", "2900.00", today.minusDays(back));
      pay(user, "Sunrise Gym", "1500.00", today.minusDays(back + 1));
    }
    pay(user, "Keells Super", "4200.00", today.minusDays(20)); // once: not a bill

    JsonNode body = list(user);
    assertEquals(2, body.path("bills").size(), body.toString());
    JsonNode netflix = named(body, "Netflix");
    assertEquals("MONTHLY", netflix.path("cadence").asText());
    assertEquals("2900.00", netflix.path("expectedAmount").asText());
    assertEquals("NETFLIX", netflix.path("billerCode").asText());
    assertEquals("STREAMING", netflix.path("billType").asText());
    assertEquals("DETECTED", netflix.path("status").asText());
    assertEquals("4400.00", body.path("monthlyTotal").asText());

    ResponseEntity<String> detail =
        rest.exchange(BILLS + "/" + netflix.path("id").asText(), HttpMethod.GET, as(user, null), String.class);
    assertEquals(3, data(detail).path("payments").size());

    // Another customer cannot see it.
    ResponseEntity<String> other =
        rest.exchange(BILLS + "/" + netflix.path("id").asText(), HttpMethod.GET, as(UUID.randomUUID(), null), String.class);
    assertEquals(404, other.getStatusCode().value());
    assertEquals(0, list(UUID.randomUUID()).path("bills").size());
  }

  @Test
  @DisplayName("rename and confirm stick; not a bill is never suggested again")
  void customerEdits() throws Exception {
    UUID user = UUID.randomUUID();
    for (int back : new int[] {70, 40, 10}) {
      pay(user, "Sunrise Gym", "1500.00", today.minusDays(back));
      pay(user, "Paper Club", "800.00", today.minusDays(back));
    }
    JsonNode body = list(user);
    String gym = named(body, "Sunrise Gym").path("id").asText();
    String paper = named(body, "Paper Club").path("id").asText();

    ResponseEntity<String> renamed =
        rest.exchange(
            BILLS + "/" + gym,
            HttpMethod.PATCH,
            as(user, "{\"name\":\"Gym membership\",\"confirm\":true,\"remindDaysBefore\":5}"),
            String.class);
    assertEquals(200, renamed.getStatusCode().value(), renamed.getBody());
    assertEquals("CONFIRMED", data(renamed).path("status").asText());

    ResponseEntity<String> dismissed =
        rest.exchange(BILLS + "/" + paper, HttpMethod.DELETE, as(user, null), String.class);
    assertEquals(200, dismissed.getStatusCode().value(), dismissed.getBody());

    pay(user, "Sunrise Gym", "1500.00", today); // a new payment re-detects everything
    JsonNode after = list(user);
    assertEquals(1, after.path("bills").size(), after.toString());
    JsonNode kept = named(after, "Gym membership");
    assertEquals(5, kept.path("remindDaysBefore").asInt());
    assertEquals("Sunrise Gym", kept.path("detectedName").asText());

    ResponseEntity<String> refused =
        rest.exchange(BILLS + "/" + gym, HttpMethod.PATCH, as(user, "{\"expectedAmount\":99}"), String.class);
    assertEquals(400, refused.getStatusCode().value(), "a found bill takes its amount from its payments");
  }

  @Test
  @DisplayName("a bill added by hand: due reminder, edits and removal")
  void manualBill() throws Exception {
    UUID user = UUID.randomUUID();
    String body =
        "{\"name\":\"House rent\",\"expectedAmount\":45000,\"cadence\":\"MONTHLY\",\"nextDueOn\":\""
            + today.plusDays(2) + "\",\"remindDaysBefore\":3}";
    ResponseEntity<String> created = rest.exchange(BILLS, HttpMethod.POST, as(user, body), String.class);
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    String id = data(created).path("id").asText();
    assertEquals("MANUAL", data(created).path("status").asText());

    list(user); // runs the check that raises "due soon"
    assertEquals(List.of(BudgetAlertType.BILL_DUE), alertTypes(id));
    list(user);
    assertEquals(1, alertTypes(id).size(), "raised once per due date");

    ResponseEntity<String> edited =
        rest.exchange(BILLS + "/" + id, HttpMethod.PATCH, as(user, "{\"expectedAmount\":47500}"), String.class);
    assertEquals("47500.00", data(edited).path("expectedAmount").asText());

    ResponseEntity<String> removed = rest.exchange(BILLS + "/" + id, HttpMethod.DELETE, as(user, null), String.class);
    assertEquals(0, data(removed).path("bills").size());

    ResponseEntity<String> invalid =
        rest.exchange(BILLS, HttpMethod.POST, as(user, "{\"name\":\"\",\"expectedAmount\":0,\"cadence\":\"DAILY\"}"), String.class);
    assertEquals(400, invalid.getStatusCode().value());
  }

  @Test
  @DisplayName("a bill that jumps raises 'bill jumped'; one that does not come raises 'missed?'")
  void jumpedAndMissed() throws Exception {
    UUID user = UUID.randomUUID();
    for (int back : new int[] {100, 70, 40}) {
      pay(user, "Lanka Water Co", "1000.00", today.minusDays(back));
      pay(user, "Hill Club", "2000.00", today.minusDays(back));
    }
    pay(user, "Lanka Water Co", "1500.00", today.minusDays(10)); // +50% on the usual 1000
    // Payments reach today, so a bill that did not come is news (not a gap in an import).
    pay(user, "Keells Super", "4200.00", today);
    JsonNode body = list(user);

    String water = named(body, "Lanka Water Co").path("id").asText();
    assertTrue(alertTypes(water).contains(BudgetAlertType.BILL_JUMPED), alertTypes(water).toString());

    JsonNode club = named(body, "Hill Club");
    assertTrue(club.path("daysUntilDue").asLong() <= -5, club.toString());
    assertTrue(alertTypes(club.path("id").asText()).contains(BudgetAlertType.BILL_MISSED));

    ResponseEntity<String> listed =
        rest.exchange("/api/insights/alerts", HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, listed.getStatusCode().value(), listed.getBody());
    boolean named = false;
    for (JsonNode a : json.readTree(listed.getBody()).path("items")) {
      named |= "Hill Club".equals(a.path("billName").asText()) && "BILL_MISSED".equals(a.path("type").asText());
    }
    assertTrue(named, "the alerts list names the bill: " + listed.getBody());
    assertFalse(alertTypes(water).contains(BudgetAlertType.BILL_MISSED));
  }
}
