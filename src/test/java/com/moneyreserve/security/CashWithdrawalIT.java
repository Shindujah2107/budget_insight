package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.repository.BiCategoryRepository;
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
 * Cash taken out at an ATM (V1.20.1) is listed with the customer's transactions but is not
 * spending: it is left out of the breakdown, the month's spend and the ledger's money-out total.
 * What the customer spends the cash on, added by hand, counts as usual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Cash withdrawals are not spending")
class CashWithdrawalIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BiCategoryRepository categories;

  private static final String INSIGHTS = "/api/insights";
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

  private UUID diningId;
  private UUID cashId;

  @BeforeEach
  void seedReferenceData() {
    ensure(BiCategory.OTHER_CODE, "Other");
    diningId = ensure("DINING_OUT", "Dining out");
    cashId = ensure(BiCategory.CASH_WITHDRAWAL_CODE, "Cash withdrawal");
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
                            .iconKey("ic_other")
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

  private void spend(UUID user, String merchant, String amount, UUID categoryId) {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\""
            + merchant + "\",\"txnDate\":\"" + today + "\",\"categoryId\":\"" + categoryId
            + "\",\"clientRef\":\"" + UUID.randomUUID() + "\"}";
    ResponseEntity<String> r =
        rest.exchange(INSIGHTS + "/transactions", HttpMethod.POST, as(user, body), String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
  }

  private JsonNode get(UUID user, String path) throws Exception {
    ResponseEntity<String> r = rest.exchange(INSIGHTS + path, HttpMethod.GET, as(user, null), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return objectMapper.readTree(r.getBody()).path("data");
  }

  @Test
  @DisplayName("listed, but left out of the breakdown, the month's spend and money out; cash spends count")
  void cashIsNotSpending() throws Exception {
    UUID user = UUID.randomUUID();
    spend(user, "Pizza Hut", "1000.00", diningId);
    spend(user, "WELLAWAT-CRM1 BR WELLAWATTE COLK", "8000.00", cashId);
    spend(user, "Lunch (cash)", "500.00", diningId);

    String month = "?from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
    JsonNode breakdown = get(user, "/breakdown" + month);
    assertEquals(0, new java.math.BigDecimal("1500.00").compareTo(new java.math.BigDecimal(breakdown.path("total").asText())),
        breakdown.toString());
    for (JsonNode c : breakdown.path("categories")) {
      assertTrue(!BiCategory.CASH_WITHDRAWAL_CODE.equals(c.path("code").asText()), "cash in the breakdown");
    }

    JsonNode ledger = get(user, "/transactions" + month);
    assertEquals(3, ledger.path("transactions").size(), "the withdrawal is still listed");
    assertEquals(0, new java.math.BigDecimal("1500.00").compareTo(new java.math.BigDecimal(ledger.path("totals").path("moneyOut").asText())),
        ledger.path("totals").toString());
    assertEquals(2, ledger.path("totals").path("moneyOutCount").asInt());

    JsonNode summary = get(user, "/summary");
    assertEquals(0, new java.math.BigDecimal("1500.00").compareTo(new java.math.BigDecimal(summary.path("spentToDate").asText("0"))),
        summary.toString());
  }
}
