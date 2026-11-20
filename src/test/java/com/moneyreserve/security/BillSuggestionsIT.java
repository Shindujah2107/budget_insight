package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillCycleStatus;
import com.moneyreserve.domain.BillStatus;
import com.moneyreserve.domain.BillType;
import com.moneyreserve.repository.BiBillCycleRepository;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.calc.MerchantRuleMatcher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
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
 * Bill suggestions (V1.27.0): new recurring payments are suggested, never added automatically;
 * the customer reviews the payments behind one and tracks it with their own schedule, or says it
 * is not recurring. Bills found before keep working as they did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Bill suggestions: reviewed, never added automatically")
class BillSuggestionsIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiBillerRepository billers;
  @Autowired private BiUserBillRepository userBills;
  @Autowired private BiBillCycleRepository cycles;
  @Autowired private BiBudgetAlertRepository alerts;
  @Autowired private com.moneyreserve.config.InsightsProperties properties;

  private static final String API = "/api/insights";
  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
  private UUID groceries;
  private UUID fuel;

  @BeforeEach
  void seed() {
    ensure(BiCategory.OTHER_CODE, "Other");
    ensure("BILLS_UTILITIES", "Bills & utilities");
    groceries = ensure("GROCERIES", "Groceries");
    fuel = ensure("FUEL", "Fuel");
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

  private ResponseEntity<String> call(UUID user, HttpMethod method, String path, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(TestTokens.bearer(user));
    return rest.exchange(API + path, method, new HttpEntity<>(body, h), String.class);
  }

  private JsonNode data(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("data");
  }

  private UUID consented() {
    UUID user = UUID.randomUUID();
    ResponseEntity<String> r =
        call(user, HttpMethod.POST, "/consent", "{\"consentVersion\":\"1.0\",\"policyHash\":\"sug\",\"channel\":\"ANDROID\"}");
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return user;
  }

  /** A card payment from a bank SMS; returns its transaction id. */
  private String cardSms(UUID user, String merchant, String amount, LocalDate date) throws Exception {
    String body =
        "{\"deviceId\":\"dev-sug\",\"platform\":\"ANDROID\",\"messages\":[{\"idempotencyKey\":\"sms-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            + "\",\"bankCode\":\"7056\",\"messageType\":\"CARD_TXN\",\"parsedAt\":\"" + OffsetDateTime.now()
            + "\",\"amount\":\"" + amount + "\",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"" + merchant
            + "\",\"txnDate\":\"" + date + "\",\"instrumentType\":\"CARD\",\"maskedTail\":\"4821\"}]}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/messages/ingest", body);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    JsonNode item = data(r).path("results").get(0);
    assertEquals("ACCEPTED", item.path("status").asText(), r.getBody());
    return item.path("transactionId").asText();
  }

  /** A payment entered by hand in a category. */
  private void spend(UUID user, String merchant, String amount, LocalDate date, UUID category) {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"" + merchant
            + "\",\"txnDate\":\"" + date + "\",\"clientRef\":\"" + UUID.randomUUID() + "\""
            + (category == null ? "" : ",\"categoryId\":\"" + category + "\"") + "}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/transactions", body);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
  }

  private JsonNode bills(UUID user) throws Exception {
    ResponseEntity<String> r = call(user, HttpMethod.GET, "/bills", null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("Netflix 3 × LKR 1,490: a suggestion with its 3 payments and the card; not a bill, no reminder, no cycle, no alert")
  void netflixSuggested() throws Exception {
    UUID user = consented();
    Set<String> ids = new HashSet<>();
    for (int back : new int[] {75, 45, 15}) {
      ids.add(cardSms(user, "NETFLIX.COM", "1490.00", today.minusDays(back)));
    }
    JsonNode list = bills(user);
    assertEquals(0, list.path("bills").size(), "suggestions are not added automatically");
    assertEquals("0.00", list.path("monthlyTotal").asText());
    assertEquals(1, list.path("suggestions").size(), list.toString());
    JsonNode s = list.path("suggestions").get(0);
    assertEquals("SUGGESTED", s.path("status").asText());
    assertEquals("1490.00", s.path("expectedAmount").asText());
    assertEquals("MONTHLY", s.path("cadence").asText());
    assertTrue(s.has("anchorDay"));

    ResponseEntity<String> why = call(user, HttpMethod.GET, "/bills/" + s.path("id").asText() + "/suggestion", null);
    assertEquals(200, why.getStatusCode().value(), why.getBody());
    JsonNode payments = data(why).path("payments");
    assertEquals(3, payments.size());
    Set<String> shown = new HashSet<>();
    for (JsonNode p : payments) {
      shown.add(p.path("transactionId").asText());
      assertEquals("4821", p.path("accountMask").asText());
      assertEquals("CARD", p.path("accountKind").asText());
      assertEquals("1490.00", p.path("amount").asText());
    }
    assertEquals(ids, shown, "the actual payments it was found from");
    assertTrue(data(why).path("months").asInt() >= 2);

    UUID billId = UUID.fromString(s.path("id").asText());
    assertTrue(cycles.findAllByUserBillIdOrderByDueOnDesc(billId).isEmpty(), "no cycle, so nothing is asked");
    assertTrue(alerts.findAll().stream().noneMatch(a -> billId.equals(a.getBillId())), "no alert");
    assertEquals(404, call(user, HttpMethod.POST, "/bills/" + billId + "/paid", "{\"clientRef\":\"x-" + UUID.randomUUID() + "\"}").getStatusCode().value(),
        "a suggestion cannot be marked paid");
  }

  @Test
  @DisplayName("regular grocery or fuel spending, and payments that vary by more than 10%, are never suggested")
  void groceriesFuelAndVaryingNotSuggested() throws Exception {
    UUID user = consented();
    for (int back : new int[] {75, 45, 15}) {
      spend(user, "Keells Super", "4200.00", today.minusDays(back), groceries);
      spend(user, "Lanka IOC Kandy", "5000.00", today.minusDays(back + 1), fuel);
    }
    spend(user, "Hill Club", "2000.00", today.minusDays(80), null);
    spend(user, "Hill Club", "2400.00", today.minusDays(50), null);
    spend(user, "Hill Club", "2000.00", today.minusDays(20), null);
    JsonNode list = bills(user);
    assertEquals(0, list.path("suggestions").size(), list.toString());
    assertEquals(0, list.path("bills").size());
  }

  @Test
  @DisplayName("'Not recurring': dismissed, and never suggested again for that payee")
  void notRecurring() throws Exception {
    UUID user = consented();
    for (int back : new int[] {75, 45, 15}) {
      cardSms(user, "Sunrise Gym", "1500.00", today.minusDays(back));
    }
    String id = bills(user).path("suggestions").get(0).path("id").asText();
    ResponseEntity<String> no = call(user, HttpMethod.PATCH, "/bills/" + id, "{\"dismiss\":true}");
    assertEquals(200, no.getStatusCode().value(), no.getBody());
    cardSms(user, "Sunrise Gym", "1500.00", today);
    JsonNode list = bills(user);
    assertEquals(0, list.path("suggestions").size(), list.toString());
    assertEquals(0, list.path("bills").size());
  }

  @Test
  @DisplayName("a bill found before suggestions (DETECTED) stays a bill, updated by its payments as before")
  void existingDetectedUnaffected() throws Exception {
    UUID user = consented();
    for (int back : new int[] {75, 45}) {
      cardSms(user, "Paper Club", "800.00", today.minusDays(back));
    }
    userBills.save(
        BiUserBill.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .payeeKey("M:" + MerchantRuleMatcher.normalise("Paper Club"))
            .detectedName("Paper Club")
            .status(BillStatus.DETECTED)
            .cadence(BillCadence.MONTHLY)
            .expectedAmount(new BigDecimal("800.00"))
            .nextDueOn(today.minusDays(15))
            .remindDaysBefore((short) 3)
            .paymentCount((short) 2)
            .createdAt(OffsetDateTime.now())
            .build());
    cardSms(user, "Paper Club", "800.00", today.minusDays(15));
    JsonNode list = bills(user);
    assertEquals(0, list.path("suggestions").size(), list.toString());
    assertEquals(1, list.path("bills").size());
    JsonNode b = list.path("bills").get(0);
    assertEquals("DETECTED", b.path("status").asText());
    assertEquals(3, b.path("paymentCount").asInt(), "still updated from its payments");
  }

  @Test
  @DisplayName("Track payment: a bill with the customer's own amount and date (never replaced by detection); its card SMS settles each month")
  void trackWithEditedValues() throws Exception {
    UUID user = consented();
    for (int back : new int[] {75, 45, 15}) {
      cardSms(user, "NETFLIX.COM", "1490.00", today.minusDays(back));
    }
    String id = bills(user).path("suggestions").get(0).path("id").asText();
    LocalDate due = today.plusDays(3);
    String body =
        "{\"name\":\"Netflix Premium\",\"expectedAmount\":1590,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + due
            + "\",\"remindDaysBefore\":2}";
    ResponseEntity<String> tracked = call(user, HttpMethod.POST, "/bills/" + id + "/track", body);
    assertEquals(200, tracked.getStatusCode().value(), tracked.getBody());
    JsonNode t = data(tracked);
    assertEquals("CONFIRMED", t.path("status").asText());
    assertTrue(t.path("tracked").asBoolean());
    assertEquals("Netflix Premium", t.path("name").asText());
    assertEquals("1590.00", t.path("expectedAmount").asText());
    assertEquals(due.toString(), t.path("nextDueOn").asText());
    assertEquals(2, t.path("remindDaysBefore").asInt());

    JsonNode list = bills(user);
    assertEquals(0, list.path("suggestions").size());
    JsonNode bill = list.path("bills").get(0);
    assertEquals("1590.00", bill.path("expectedAmount").asText(), "detection never replaces the customer's amount");
    assertEquals(due.toString(), bill.path("nextDueOn").asText());
    assertEquals(BillCycleStatus.DUE, cycles.findByUserBillIdAndDueOn(UUID.fromString(id), due).orElseThrow().getStatus());
    assertEquals(400, call(user, HttpMethod.POST, "/bills/" + id + "/track", body).getStatusCode().value(), "tracked once");

    // This month's card SMS: the cycle is paid by it and the bill moves on a month.
    String smsId = cardSms(user, "NETFLIX.COM", "1590.00", today);
    JsonNode after = bills(user).path("bills").get(0);
    assertEquals(due.plusMonths(1).toString(), after.path("nextDueOn").asText(), after.toString());
    assertEquals("1590.00", after.path("expectedAmount").asText());
    var paid = cycles.findByUserBillIdAndDueOn(UUID.fromString(id), due).orElseThrow();
    assertEquals(BillCycleStatus.PAID, paid.getStatus());
    assertEquals(UUID.fromString(smsId), paid.getTransactionId());
  }

  @Test
  @DisplayName("only a suggestion can be tracked; Cancel (no call) leaves it a suggestion")
  void trackOnlySuggestions() throws Exception {
    UUID user = consented();
    ResponseEntity<String> manual =
        call(user, HttpMethod.POST, "/bills",
            "{\"name\":\"Rent\",\"expectedAmount\":58000,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + today + "\"}");
    String id = data(manual).path("id").asText();
    String body = "{\"name\":\"Rent\",\"expectedAmount\":58000,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + today + "\"}";
    assertEquals(400, call(user, HttpMethod.POST, "/bills/" + id + "/track", body).getStatusCode().value());
    for (int back : new int[] {75, 45, 15}) {
      cardSms(user, "NETFLIX.COM", "1490.00", today.minusDays(back));
    }
    bills(user);
    JsonNode again = bills(user);
    assertEquals(1, again.path("suggestions").size());
    assertFalse(again.path("suggestions").get(0).path("tracked").asBoolean());
  }

  // ---- the switch: free now, Premium later -----------------------------------------------------

  private UUID withNetflix() throws Exception {
    UUID user = consented();
    for (int back : new int[] {75, 45, 15}) {
      cardSms(user, "NETFLIX.COM", "1490.00", today.minusDays(back));
    }
    return user;
  }

  private String trackBody() {
    return "{\"name\":\"Netflix\",\"expectedAmount\":1490,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + today.plusDays(5) + "\"}";
  }

  @Test
  @DisplayName("on (the default, free): suggestions shown and trackable, the flag says so")
  void switchOn() throws Exception {
    UUID user = withNetflix();
    JsonNode list = bills(user);
    assertTrue(list.path("suggestionsEnabled").asBoolean());
    assertEquals(1, list.path("suggestions").size());
  }

  @Test
  @DisplayName("Premium-only while the Premium gate is open: still on for everyone")
  void premiumOnlyGateOpen() throws Exception {
    properties.getBills().setSuggestionsPremium(true);
    try {
      UUID user = withNetflix();
      JsonNode list = bills(user);
      assertTrue(list.path("suggestionsEnabled").asBoolean());
      assertEquals(1, list.path("suggestions").size());
    } finally {
      properties.getBills().setSuggestionsPremium(false);
    }
  }

  @Test
  @DisplayName("Premium-only with the gate on (not Premium): hidden and refused, found silently, back as soon as it is opened; bills keep working")
  void premiumOnlyGateOn() throws Exception {
    UUID user;
    String id;
    properties.getBills().setSuggestionsPremium(true);
    properties.getBills().setPremiumGate(true);
    try {
      user = withNetflix();
      ResponseEntity<String> manual =
          call(user, HttpMethod.POST, "/bills",
              "{\"name\":\"Rent\",\"expectedAmount\":58000,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + today + "\",\"remindDaysBefore\":1}");
      assertEquals(201, manual.getStatusCode().value(), manual.getBody());
      JsonNode list = bills(user);
      assertFalse(list.path("suggestionsEnabled").asBoolean());
      assertEquals(0, list.path("suggestions").size(), "no card");
      assertEquals(1, list.path("bills").size(), "a bill added by hand still works");
      BiUserBill hidden =
          userBills.findAllByUserIdOrderByNextDueOnAsc(user).stream()
              .filter(b -> b.getStatus() == BillStatus.SUGGESTED)
              .findFirst()
              .orElseThrow(() -> new AssertionError("found silently"));
      id = hidden.getId().toString();
      assertEquals(403, call(user, HttpMethod.GET, "/bills/" + id + "/suggestion", null).getStatusCode().value());
      assertEquals(403, call(user, HttpMethod.POST, "/bills/" + id + "/track", trackBody()).getStatusCode().value());
      String paid = data(call(user, HttpMethod.POST, "/bills/" + list.path("bills").get(0).path("id").asText() + "/paid",
          "{\"clientRef\":\"r-" + UUID.randomUUID() + "\"}")).path("payment").path("transaction").path("id").asText();
      assertFalse(paid.isEmpty(), "marking a bill paid still works");
    } finally {
      properties.getBills().setPremiumGate(false);
      properties.getBills().setSuggestionsPremium(false);
    }
    JsonNode opened = bills(user);
    assertEquals(1, opened.path("suggestions").size(), "appears at once when opened");
    assertEquals(id, opened.path("suggestions").get(0).path("id").asText());
  }

  @Test
  @DisplayName("off: no suggestions, review or tracking for anyone")
  void switchOff() throws Exception {
    properties.getBills().setSuggestions(false);
    try {
      UUID user = withNetflix();
      JsonNode list = bills(user);
      assertFalse(list.path("suggestionsEnabled").asBoolean());
      assertEquals(0, list.path("suggestions").size());
      String id =
          userBills.findAllByUserIdOrderByNextDueOnAsc(user).stream()
              .filter(b -> b.getStatus() == BillStatus.SUGGESTED)
              .findFirst()
              .orElseThrow()
              .getId()
              .toString();
      assertEquals(403, call(user, HttpMethod.POST, "/bills/" + id + "/track", trackBody()).getStatusCode().value());
    } finally {
      properties.getBills().setSuggestions(true);
    }
  }
}
