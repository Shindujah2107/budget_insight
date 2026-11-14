package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.repository.BiCategoryRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * "Between my accounts": a transfer the customer marks as their own money moving is left out of
 * spending, income and budgets, but stays in the list; unmarking puts it back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Transfers between the customer's own accounts")
class OwnAccountTransferIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private ObjectMapper objectMapper;

  private static final String TRANSACTIONS = "/api/insights/transactions";

  private UUID transfersId;
  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
  private final LocalDate monthStart = today.withDayOfMonth(1);

  @BeforeEach
  void seedReferenceData() {
    transfersId = ensure("TRANSFERS", "Transfers");
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

  private String range() {
    return "from=" + monthStart + "&to=" + monthStart.plusMonths(1).minusDays(1);
  }

  private UUID create(UUID user, String amount, String direction) throws Exception {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"" + direction
            + "\",\"merchant\":\"Online Transfer\",\"txnDate\":\"" + today
            + "\",\"categoryId\":\"" + transfersId + "\",\"clientRef\":\"" + UUID.randomUUID() + "\"}";
    ResponseEntity<String> r = rest.exchange(TRANSACTIONS, HttpMethod.POST, as(user, body), String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return UUID.fromString(objectMapper.readTree(r.getBody()).path("data").path("transaction").path("id").asText());
  }

  private ResponseEntity<String> mark(UUID user, UUID id, String movement) {
    return rest.exchange(
        TRANSACTIONS + "/" + id + "?" + range(),
        HttpMethod.PATCH,
        as(user, "{\"movement\":\"" + movement + "\"}"),
        String.class);
  }

  private JsonNode totals(UUID user) throws Exception {
    ResponseEntity<String> r =
        rest.exchange(TRANSACTIONS + "?" + range(), HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return objectMapper.readTree(r.getBody()).path("data");
  }

  private String spent(UUID user) throws Exception {
    ResponseEntity<String> r =
        rest.exchange("/api/insights/breakdown?" + range(), HttpMethod.GET, as(user, null), String.class);
    return objectMapper.readTree(r.getBody()).path("data").path("total").asText();
  }

  @Test
  @DisplayName("marked: out of money out, money in and spending, still listed; unmarked: back in")
  void markAndUnmark() throws Exception {
    UUID user = UUID.randomUUID();
    UUID out = create(user, "5000.00", "DEBIT");
    UUID in = create(user, "5000.00", "CREDIT");
    create(user, "250.00", "DEBIT");
    assertEquals("5250.00", spent(user));

    ResponseEntity<String> r = mark(user, out, "OWN_ACCOUNT");
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    assertEquals(
        "OWN_ACCOUNT",
        objectMapper.readTree(r.getBody()).path("data").path("transaction").path("movement").asText());
    assertEquals(200, mark(user, in, "OWN_ACCOUNT").getStatusCode().value());

    JsonNode data = totals(user);
    assertEquals("250.00", data.path("totals").path("moneyOut").asText());
    assertEquals("0.00", data.path("totals").path("moneyIn").asText());
    assertEquals(3, data.path("page").path("totalElements").asInt(), "still listed");
    assertEquals("250.00", spent(user));

    assertEquals(200, mark(user, out, "NONE").getStatusCode().value());
    assertEquals("5250.00", spent(user));
  }

  @Test
  @DisplayName("another customer cannot mark my transfer")
  void onlyTheOwner() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID id = create(owner, "100.00", "DEBIT");
    assertEquals(404, mark(UUID.randomUUID(), id, "OWN_ACCOUNT").getStatusCode().value());
  }

  @Test
  @DisplayName("an unknown movement value is refused")
  void validated() throws Exception {
    UUID user = UUID.randomUUID();
    UUID id = create(user, "100.00", "DEBIT");
    assertEquals(400, mark(user, id, "BANK_TO_CASH").getStatusCode().value());
  }
}
