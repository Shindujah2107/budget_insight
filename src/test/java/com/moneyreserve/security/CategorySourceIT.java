package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.repository.BiUserMerchantCategoryRepository;
import com.moneyreserve.service.jobs.CategorySourceBackfill;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
 * Why a transaction has its category (V1.17.0): set on every write, backfilled for older rows,
 * summed as "needs a category", and a customer's "always use this category" choice that touches
 * only their own rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Category source and merchant choices")
class CategorySourceIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiUserMerchantCategoryRepository choices;
  @Autowired private CategorySourceBackfill backfill;

  private static final String TRANSACTIONS = "/api/insights/transactions";
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

  private UUID diningId;
  private UUID otherId;

  @BeforeEach
  void seedReferenceData() {
    diningId = ensure("DINING_OUT", "Dining out");
    otherId = ensure(BiCategory.OTHER_CODE, "Other");
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

  private JsonNode create(UUID user, String merchant, UUID categoryId) throws Exception {
    String body =
        "{\"amount\":120.00,\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\""
            + merchant
            + "\",\"txnDate\":\""
            + today
            + "\""
            + (categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"")
            + ",\"clientRef\":\""
            + UUID.randomUUID()
            + "\"}";
    ResponseEntity<String> r = rest.exchange(TRANSACTIONS, HttpMethod.POST, as(user, body), String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return objectMapper.readTree(r.getBody()).path("data").path("transaction");
  }

  private JsonNode ledger(UUID user, String extra) throws Exception {
    String month = "from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
    ResponseEntity<String> r =
        rest.exchange(TRANSACTIONS + "?" + month + extra, HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return objectMapper.readTree(r.getBody()).path("data");
  }

  @Test
  @DisplayName("a chosen category is USER; nothing matching is FALLBACK and counts as needing a category")
  void sourceOnCreateAndTotals() throws Exception {
    UUID user = UUID.randomUUID();
    assertEquals("USER", create(user, "Pizza Hut", diningId).path("categorySource").asText());
    JsonNode unmatched = create(user, "ZZ Unknown Shop 4417", null);
    assertEquals("FALLBACK", unmatched.path("categorySource").asText());
    assertEquals(otherId.toString(), unmatched.path("categoryId").asText());

    JsonNode data = ledger(user, "");
    assertEquals("120.00", data.path("totals").path("uncategorised").asText());
    assertEquals(1, data.path("totals").path("uncategorisedCount").asInt());
    JsonNode only = ledger(user, "&uncategorised=true");
    assertEquals(1, only.path("transactions").size());
    assertEquals("FALLBACK", only.path("transactions").get(0).path("categorySource").asText());
  }

  @Test
  @DisplayName("recategorising sets USER and the row stops needing a category")
  void recategoriseSetsUser() throws Exception {
    UUID user = UUID.randomUUID();
    String id = create(user, "ZZ Corner Stall 1", null).path("id").asText();
    ResponseEntity<String> r =
        rest.exchange(
            TRANSACTIONS + "/" + id,
            HttpMethod.PATCH,
            as(user, "{\"categoryId\":\"" + diningId + "\"}"),
            String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    assertEquals(
        "USER",
        objectMapper.readTree(r.getBody()).path("data").path("transaction").path("categorySource").asText());
    assertEquals(0, ledger(user, "").path("totals").path("uncategorisedCount").asInt());
  }

  @Test
  @DisplayName("'always use this category' moves only this customer's rows and their future ones")
  void merchantChoiceIsPersonal() throws Exception {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    String first = create(alice, "ZZ Kottu Hut Wellawatte", null).path("id").asText();
    String second = create(alice, "zz kottu  hut wellawatte", null).path("id").asText();
    String bobs = create(bob, "ZZ Kottu Hut Wellawatte", null).path("id").asText();

    ResponseEntity<String> r =
        rest.exchange(
            TRANSACTIONS + "/" + first,
            HttpMethod.PATCH,
            as(alice, "{\"categoryId\":\"" + diningId + "\",\"applyToMerchant\":true}"),
            String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());

    BiTransaction moved = transactions.findById(UUID.fromString(second)).orElseThrow();
    assertEquals(diningId, moved.getCategoryId(), "same merchant, different spacing and case");
    assertEquals(CategorySource.USER, moved.getCategorySource());
    BiTransaction untouched = transactions.findById(UUID.fromString(bobs)).orElseThrow();
    assertEquals(otherId, untouched.getCategoryId(), "another customer's row is never changed");
    assertEquals(CategorySource.FALLBACK, untouched.getCategorySource());

    assertEquals(1, choices.findAllByUserIdOrderByMerchantKeyAsc(alice).size());
    assertEquals(0, choices.findAllByUserIdOrderByMerchantKeyAsc(bob).size());

    // Her next row from that merchant follows her choice; his does not.
    JsonNode next = create(alice, "ZZ KOTTU HUT WELLAWATTE", null);
    assertEquals(diningId.toString(), next.path("categoryId").asText());
    assertEquals("USER", next.path("categorySource").asText());
    assertEquals("FALLBACK", create(bob, "ZZ Kottu Hut Wellawatte", null).path("categorySource").asText());
  }

  @Test
  @DisplayName("older rows are backfilled: customer choice USER, non-Other RULE, unmatched Other FALLBACK")
  void backfill() {
    UUID user = UUID.randomUUID();
    BiTransaction chosen = legacy(user, otherId, true, "ZZ Legacy One");
    BiTransaction ruled = legacy(user, diningId, false, "ZZ Legacy Two");
    BiTransaction unmatched = legacy(user, otherId, false, "ZZ Legacy Three");

    assertTrue(backfill.run() >= 3);
    assertEquals(CategorySource.USER, transactions.findById(chosen.getId()).orElseThrow().getCategorySource());
    assertEquals(CategorySource.RULE, transactions.findById(ruled.getId()).orElseThrow().getCategorySource());
    assertEquals(
        CategorySource.FALLBACK, transactions.findById(unmatched.getId()).orElseThrow().getCategorySource());
    assertEquals(0, backfill.run(), "a second run has nothing left to do");
  }

  private BiTransaction legacy(UUID user, UUID category, boolean userChosen, String merchant) {
    return transactions.save(
        BiTransaction.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .amount(new BigDecimal("50.00"))
            .currency("LKR")
            .direction(TxnDirection.DEBIT)
            .merchant(merchant)
            .txnDate(today)
            .categoryId(category)
            .autoCategoryId(category)
            .isUserCategorised(userChosen)
            .source(TxnSource.MANUAL)
            .idempotencyKey("legacy-" + UUID.randomUUID())
            .createdAt(OffsetDateTime.now())
            .build());
  }
}
