package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.repository.BiCategoryRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * GET /api/insights/transactions: the one list with money in as well as money out.
 *
 * <p>What it must never do: show another customer's rows, count a deleted row, repeat or skip a row
 * between pages, or let a page's totals stand in for the period's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Ledger list (both directions)")
class LedgerIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private ObjectMapper objectMapper;

  private static final String TRANSACTIONS = "/api/insights/transactions";

  private UUID diningId;
  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
  private final LocalDate monthStart = today.withDayOfMonth(1);

  @BeforeEach
  void seedReferenceData() {
    diningId = ensure("DINING_OUT", "Dining out");
    ensure(BiCategory.OTHER_CODE, "Other");
  }

  private UUID ensure(String code, String name) {
    return categories
        .findByCodeAndIsActiveTrue(code)
        .map(BiCategory::getId)
        .orElseGet(
            () ->
                categories
                    .save(
                        BiCategory.builder()
                            .id(UUID.randomUUID())
                            .code(code)
                            .nameEn(name)
                            .iconKey(code.toLowerCase())
                            .sortOrder((short) 1)
                            .isActive(true)
                            .createdAt(OffsetDateTime.now())
                            .build())
                    .getId());
  }

  private HttpEntity<String> as(UUID user, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(TestTokens.bearer(user));
    return new HttpEntity<>(body, h);
  }

  private UUID create(UUID user, String amount, String direction, LocalDate date) throws Exception {
    String body =
        "{\"amount\":"
            + amount
            + ",\"currency\":\"LKR\",\"direction\":\""
            + direction
            + "\",\"merchant\":\"Ledger Test\",\"txnDate\":\""
            + date
            + "\",\"categoryId\":\""
            + diningId
            + "\",\"clientRef\":\""
            + UUID.randomUUID()
            + "\"}";
    ResponseEntity<String> response =
        rest.exchange(TRANSACTIONS, HttpMethod.POST, as(user, body), String.class);
    assertTrue(response.getStatusCode().is2xxSuccessful(), response.getBody());
    return UUID.fromString(
        objectMapper.readTree(response.getBody()).path("data").path("transaction").path("id").asText());
  }

  private JsonNode get(UUID user, String query) throws Exception {
    ResponseEntity<String> response =
        rest.exchange(TRANSACTIONS + "?" + query, HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, response.getStatusCode().value(), response.getBody());
    return objectMapper.readTree(response.getBody()).path("data");
  }

  private String month() {
    return "from=" + monthStart + "&to=" + monthStart.plusMonths(1).minusDays(1);
  }

  @Test
  @DisplayName("lists money out and money in, with whole-period totals and no net flow")
  void bothDirectionsAndTotals() throws Exception {
    UUID user = UUID.randomUUID();
    create(user, "100.00", "DEBIT", today);
    create(user, "250.50", "DEBIT", monthStart);
    create(user, "1000.00", "CREDIT", today);

    JsonNode data = get(user, month());
    assertEquals(3, data.path("page").path("totalElements").asInt());
    JsonNode totals = data.path("totals");
    assertEquals("350.50", totals.path("moneyOut").asText());
    assertEquals(2, totals.path("moneyOutCount").asInt());
    assertEquals("1000.00", totals.path("moneyIn").asText());
    assertEquals(1, totals.path("moneyInCount").asInt());
    assertEquals("RECORDED", totals.path("incomeCoverage").asText());
    assertTrue(totals.path("net").isMissingNode(), "no net flow is claimed");

    JsonNode credits = get(user, month() + "&direction=CREDIT");
    assertEquals(1, credits.path("transactions").size());
    assertEquals("CREDIT", credits.path("transactions").get(0).path("direction").asText());
    // Totals describe the period, not the direction filter or the page.
    assertEquals("350.50", credits.path("totals").path("moneyOut").asText());
  }

  @Test
  @DisplayName("pages never repeat or skip a row, and the order is newest first")
  void stablePaging() throws Exception {
    UUID user = UUID.randomUUID();
    Set<UUID> created = new HashSet<>();
    for (int i = 0; i < 7; i++) {
      created.add(create(user, (10 + i) + ".00", i % 3 == 0 ? "CREDIT" : "DEBIT", today));
    }
    List<String> seen = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      JsonNode data = get(user, month() + "&size=3&page=" + page);
      assertEquals(3, data.path("page").path("totalPages").asInt());
      data.path("transactions").forEach(t -> seen.add(t.path("id").asText()));
    }
    assertEquals(7, seen.size());
    assertEquals(7, new HashSet<>(seen).size(), "no row appears twice");
    Set<String> expected = new HashSet<>();
    created.forEach(id -> expected.add(id.toString()));
    assertEquals(expected, new HashSet<>(seen), "no row is skipped");
  }

  @Test
  @DisplayName("another customer's rows and deleted rows are never listed or counted")
  void ownershipAndDeletion() throws Exception {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    create(alice, "500.00", "DEBIT", today);
    UUID gone = create(alice, "70.00", "DEBIT", today);
    create(bob, "999.00", "DEBIT", today);
    rest.exchange(TRANSACTIONS + "/" + gone, HttpMethod.DELETE, as(alice, null), String.class);

    JsonNode data = get(alice, month());
    assertEquals(1, data.path("transactions").size());
    assertEquals("500.00", data.path("totals").path("moneyOut").asText());
    data.path("transactions").forEach(t -> assertFalse(t.path("amount").asText().equals("999.00")));

    // Naming another customer's account cannot reach their rows either: it just matches nothing.
    JsonNode foreign = get(alice, month() + "&accountId=" + UUID.randomUUID());
    assertEquals(0, foreign.path("transactions").size());
  }

  @Test
  @DisplayName("the period boundary is the calendar month: the last day before it is excluded")
  void monthBoundary() throws Exception {
    UUID user = UUID.randomUUID();
    create(user, "40.00", "DEBIT", monthStart);
    create(user, "60.00", "DEBIT", monthStart.minusDays(1));
    JsonNode data = get(user, month());
    assertEquals(1, data.path("transactions").size());
    assertEquals("40.00", data.path("totals").path("moneyOut").asText());
  }

  @Test
  @DisplayName("an unknown direction is refused, not ignored")
  void badDirection() {
    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "?direction=SIDEWAYS",
            HttpMethod.GET,
            as(UUID.randomUUID(), null),
            String.class);
    assertEquals(400, response.getStatusCode().value(), response.getBody());
  }
}
