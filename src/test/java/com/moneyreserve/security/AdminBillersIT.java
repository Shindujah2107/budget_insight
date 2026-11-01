package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillStatus;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiUserBillRepository;
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
 * Spending → Billers: drafts published like categories reach detection; a biller behind customers'
 * bills cannot be deleted; recurring payees no biller matches are listed as counts only, from three
 * customers up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: billers")
class AdminBillersIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiBillerRepository billers;
  @Autowired private BiUserBillRepository userBills;
  @Autowired private BiCategoryRepository categories;

  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));

  @BeforeEach
  void seedOther() {
    if (categories.findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE).isEmpty()) {
      categories.save(
          BiCategory.builder()
              .id(UUID.randomUUID())
              .code(BiCategory.OTHER_CODE)
              .nameEn("Other")
              .iconKey("ic_other")
              .sortOrder((short) 17)
              .isActive(true)
              .createdAt(OffsetDateTime.now())
              .build());
    }
  }

  private AdminTestSupport admin() {
    return new AdminTestSupport(rest, UUID.randomUUID());
  }

  private static String biller(String code, String terms) {
    return "{\"code\":\"" + code + "\",\"nameEn\":\"Biller " + code + "\",\"nameTa\":\"கட்டணம்\","
        + "\"billType\":\"TELECOM\",\"terms\":[" + terms + "],\"iconKey\":\"ic_bills_utilities\","
        + "\"colourHex\":\"#1aacab\",\"sortOrder\":70,\"active\":true}";
  }

  private void pay(UUID user, String merchant, LocalDate date) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(user));
    h.setContentType(MediaType.APPLICATION_JSON);
    String body =
        "{\"amount\":1200.00,\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"" + merchant
            + "\",\"txnDate\":\"" + date + "\",\"clientRef\":\"" + UUID.randomUUID() + "\"}";
    ResponseEntity<String> r =
        rest.exchange("/api/insights/transactions", HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
  }

  private JsonNode bills(UUID user) throws Exception {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(user));
    return AdminTestSupport.data(
        rest.exchange("/api/insights/bills", HttpMethod.GET, new HttpEntity<>(h), String.class));
  }

  @Test
  @DisplayName("a published biller groups a customer's payments by its name terms")
  void publishedBillerReachesDetection() throws Exception {
    AdminTestSupport admin = admin();
    admin.ensureOther();
    ResponseEntity<String> created =
        admin.config(HttpMethod.POST, "/billers", biller("ZZ_TEL", "\"ZZ TEL\",\"ZEETEL\""));
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    JsonNode item = AdminTestSupport.data(created).path("item");
    assertEquals("DRAFT_NEW", item.path("state").asText());
    assertEquals("#1AACAB", item.path("effective").path("colourHex").asText());
    assertTrue(admin.publishAll().getStatusCode().is2xxSuccessful());
    assertTrue(billers.findByCode("ZZ_TEL").isPresent());

    UUID user = UUID.randomUUID();
    pay(user, "ZZ TEL BILL PAY 0771", today.minusDays(40));
    pay(user, "ZeeTel online", today.minusDays(10));
    JsonNode bill = bills(user).path("bills").get(0);
    assertEquals("Biller ZZ_TEL", bill.path("name").asText(), "two payments are enough for a biller");
    assertEquals("ZZ_TEL", bill.path("billerCode").asText());
    assertEquals("கட்டணம்", bill.path("billerNameTa").asText());
    assertEquals("#1AACAB", bill.path("colourHex").asText());

    UUID id = UUID.fromString(item.path("id").asText());
    ResponseEntity<String> delete = admin.config(HttpMethod.DELETE, "/billers/" + id, null);
    assertEquals(400, delete.getStatusCode().value(), "customers' bills come from it: " + delete.getBody());
    ResponseEntity<String> off = admin.config(HttpMethod.POST, "/billers/" + id + "/deactivate", null);
    assertEquals(200, off.getStatusCode().value(), off.getBody());
    assertEquals("DRAFT_DEACTIVATED", AdminTestSupport.data(off).path("item").path("state").asText());
  }

  @Test
  @DisplayName("duplicate codes and unusable name terms are refused")
  void invalidBillers() throws Exception {
    AdminTestSupport admin = admin();
    assertEquals(201, admin.config(HttpMethod.POST, "/billers", biller("ZZ_DUP", "\"ZZDUP\"")).getStatusCode().value());
    assertEquals(409, admin.config(HttpMethod.POST, "/billers", biller("ZZ_DUP", "\"ZZDUP\"")).getStatusCode().value());
    assertEquals(400, admin.config(HttpMethod.POST, "/billers", biller("ZZ_BAD", "\"-\"")).getStatusCode().value());
    assertEquals(400, admin.config(HttpMethod.POST, "/billers", biller("ZZ_NONE", "")).getStatusCode().value());
  }

  @Test
  @DisplayName("recurring payees no biller matches: counts only, from three customers up")
  void recurringPayees() throws Exception {
    for (int i = 0; i < 3; i++) {
      follow("M:ZZ HILL CLUB", BillCadence.MONTHLY);
    }
    follow("M:ZZ LONE PAYEE", BillCadence.MONTHLY);
    follow("M:ZZ LONE PAYEE", BillCadence.MONTHLY);

    JsonNode list = AdminTestSupport.data(admin().config(HttpMethod.GET, "/recurring-payees", null));
    JsonNode club = null;
    for (JsonNode row : list) {
      assertTrue(!"ZZ LONE PAYEE".equals(row.path("payee").asText()), "two customers are not listed");
      if ("ZZ HILL CLUB".equals(row.path("payee").asText())) {
        club = row;
      }
    }
    assertTrue(club != null, list.toString());
    assertEquals(3, club.path("customers").asInt());
    assertEquals("MONTHLY", club.path("cadence").asText());
    assertEquals("ZZ HILL CLUB", club.path("suggestedTerms").get(0).asText());
    assertEquals(4, club.size(), "name, count, cadence and terms only: " + club);

    AdminTestSupport admin = admin();
    admin.ensureOther();
    assertEquals(201, admin.config(HttpMethod.POST, "/billers", biller("ZZ_HILL", "\"ZZ HILL CLUB\"")).getStatusCode().value());
    assertTrue(admin.publishAll().getStatusCode().is2xxSuccessful());
    for (JsonNode row : AdminTestSupport.data(admin.config(HttpMethod.GET, "/recurring-payees", null))) {
      assertTrue(!"ZZ HILL CLUB".equals(row.path("payee").asText()), "a published biller covers it before bills refresh");
    }
  }

  private void follow(String payeeKey, BillCadence cadence) {
    userBills.save(
        BiUserBill.builder()
            .id(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .payeeKey(payeeKey)
            .detectedName("Someone's spelling " + UUID.randomUUID())
            .status(BillStatus.DETECTED)
            .cadence(cadence)
            .expectedAmount(new BigDecimal("2000.00"))
            .nextDueOn(today.plusDays(5))
            .remindDaysBefore((short) 3)
            .paymentCount((short) 3)
            .createdAt(OffsetDateTime.now())
            .build());
  }
}
