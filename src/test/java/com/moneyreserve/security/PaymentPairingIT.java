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
 * Payments the customer recorded (a confirmed "attempted, use code" payment, a bill marked paid)
 * and the bank SMS for the same payment are one payment, whichever arrives first (V1.23.0).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Recorded payments and bank SMS are counted once")
class PaymentPairingIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private ObjectMapper json;

  private static final String API = "/api/insights";
  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
  private UUID transfers;
  private UUID bills;

  @BeforeEach
  void seed() {
    transfers = ensure("TRANSFERS", "Transfers");
    bills = ensure("BILLS_UTILITIES", "Bills & utilities");
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

  // ---- helpers -------------------------------------------------------------------------------

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
        call(user, HttpMethod.POST, "/consent", "{\"consentVersion\":\"1.0\",\"policyHash\":\"pay\",\"channel\":\"ANDROID\"}");
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return user;
  }

  /** A bank SMS debit as the phone sends it. */
  private JsonNode sms(UUID user, String key, String bank, String type, String amount) throws Exception {
    String body =
        "{\"deviceId\":\"dev-pair\",\"platform\":\"ANDROID\",\"messages\":[{\"idempotencyKey\":\"" + key
            + "\",\"bankCode\":\"" + bank + "\",\"messageType\":\"" + type
            + "\",\"parsedAt\":\"" + OffsetDateTime.now() + "\",\"amount\":\"" + amount
            + "\",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Online Transfer\",\"txnDate\":\""
            + today + "\",\"instrumentType\":\"BANK\"}]}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/messages/ingest", body);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  /** "Yes, my LKR … payment went through" after an attempt message. */
  private JsonNode confirm(UUID user, String clientRef, String bank, String amount) throws Exception {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Transfer to Other Banks\","
            + "\"txnDate\":\"" + today + "\",\"categoryId\":\"" + transfers + "\",\"clientRef\":\"" + clientRef
            + "\",\"origin\":\"ATTEMPT\",\"originBankCode\":\"" + bank + "\"}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/transactions", body);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return data(r);
  }

  private JsonNode ledger(UUID user) throws Exception {
    ResponseEntity<String> r =
        call(user, HttpMethod.GET, "/transactions?from=" + today.withDayOfMonth(1) + "&to=" + today.plusDays(1), null);
    return data(r);
  }

  private String key() {
    return "sms-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  // ---- attempts ------------------------------------------------------------------------------

  @Test
  @DisplayName("attempt confirmed, then the bank SMS: paired, counted once; the SMS resent stays a duplicate")
  void confirmedThenSms() throws Exception {
    UUID user = consented();
    String id = confirm(user, "att-" + UUID.randomUUID(), "7056", "6800.00").path("transaction").path("id").asText();
    String k = key();
    JsonNode in = sms(user, k, "7056", "ACCOUNT_TXN", "6800.00");
    assertEquals(0, in.path("accepted").asInt());
    assertEquals(1, in.path("duplicates").asInt());
    assertEquals(id, in.path("results").get(0).path("transactionId").asText());
    assertEquals(1, sms(user, k, "7056", "ACCOUNT_TXN", "6800.00").path("duplicates").asInt());
    JsonNode l = ledger(user);
    assertEquals(1, l.path("page").path("totalElements").asInt());
    assertEquals("6800.00", l.path("totals").path("moneyOut").asText());
  }

  @Test
  @DisplayName("bank SMS first, then the attempt confirmed: the SMS row is the payment; a retry is a duplicate too")
  void smsThenConfirmed() throws Exception {
    UUID user = consented();
    JsonNode in = sms(user, key(), "7056", "ACCOUNT_TXN", "6800.00");
    String smsId = in.path("results").get(0).path("transactionId").asText();
    String ref = "att-" + UUID.randomUUID();
    JsonNode c = confirm(user, ref, "7056", "6800.00");
    assertTrue(c.path("duplicate").asBoolean());
    assertEquals(smsId, c.path("transaction").path("id").asText());
    assertEquals(smsId, confirm(user, ref, "7056", "6800.00").path("transaction").path("id").asText());
    assertEquals(1, ledger(user).path("page").path("totalElements").asInt());
  }

  @Test
  @DisplayName("an SMS from another bank is a different payment")
  void otherBankNotPaired() throws Exception {
    UUID user = consented();
    confirm(user, "att-" + UUID.randomUUID(), "7056", "6800.00");
    assertEquals(1, sms(user, key(), "7010", "ACCOUNT_TXN", "6800.00").path("accepted").asInt());
    assertEquals("13600.00", ledger(user).path("totals").path("moneyOut").asText());
  }

  @Test
  @DisplayName("two payments of the same amount on one day: one confirmed attempt pairs with one SMS, the second SMS is new")
  void twoSameAmountPaymentsStayTwo() throws Exception {
    UUID user = consented();
    confirm(user, "att-" + UUID.randomUUID(), "7056", "500.00");
    assertEquals(1, sms(user, key(), "7056", "ACCOUNT_TXN", "500.00").path("duplicates").asInt());
    assertEquals(1, sms(user, key(), "7056", "ACCOUNT_TXN", "500.00").path("accepted").asInt());
    JsonNode l = ledger(user);
    assertEquals(2, l.path("page").path("totalElements").asInt());
    assertEquals("1000.00", l.path("totals").path("moneyOut").asText());
  }

  @Test
  @DisplayName("a device can never ingest a payment attempt as a transaction")
  void attemptTypeRefused() throws Exception {
    UUID user = consented();
    JsonNode in = sms(user, key(), "7056", "PAYMENT_ATTEMPT", "6800.00");
    assertEquals(1, in.path("rejected").asInt());
    assertEquals(0, ledger(user).path("page").path("totalElements").asInt());
  }

  // ---- bills ---------------------------------------------------------------------------------

  private String rent(UUID user, LocalDate due) throws Exception {
    ResponseEntity<String> r =
        call(user, HttpMethod.POST, "/bills",
            "{\"name\":\"House rent\",\"expectedAmount\":58000,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + due + "\"}");
    assertEquals(201, r.getStatusCode().value(), r.getBody());
    return data(r).path("id").asText();
  }

  private JsonNode paid(UUID user, String billId, String extra) throws Exception {
    ResponseEntity<String> r =
        call(user, HttpMethod.POST, "/bills/" + billId + "/paid", "{\"clientRef\":\"bill-" + UUID.randomUUID() + "\"" + extra + "}");
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  @Test
  @DisplayName("bill paid from the reminder, then the bank SMS: paired; the cycle can't be paid twice")
  void billThenSms() throws Exception {
    UUID user = consented();
    String bill = rent(user, today);
    JsonNode p = paid(user, bill, "");
    String id = p.path("payment").path("transaction").path("id").asText();
    assertEquals(today.plusMonths(1).toString(), p.path("bill").path("nextDueOn").asText(), "moved on a cycle");
    assertEquals(id, sms(user, key(), "7010", "ACCOUNT_TXN", "58000.00").path("results").get(0).path("transactionId").asText());
    assertEquals(1, ledger(user).path("page").path("totalElements").asInt());
    assertEquals("58000.00", ledger(user).path("totals").path("moneyOut").asText());

    // The next due date moved on a month; a second "paid" this cycle records nothing.
    JsonNode again = paid(user, bill, "");
    assertTrue(again.path("alreadyPaid").asBoolean(), again.toString());
    assertEquals(1, ledger(user).path("page").path("totalElements").asInt());
  }

  @Test
  @DisplayName("bill paid after its SMS arrived: the SMS row becomes the bill's payment, nothing added")
  void smsThenBill() throws Exception {
    UUID user = consented();
    String smsId = sms(user, key(), "7056", "ACCOUNT_TXN", "58000.00").path("results").get(0).path("transactionId").asText();
    String bill = rent(user, today);
    JsonNode p = paid(user, bill, "");
    assertEquals(smsId, p.path("payment").path("transaction").path("id").asText());
    assertTrue(p.path("payment").path("duplicate").asBoolean());
    assertEquals(1, ledger(user).path("page").path("totalElements").asInt());
  }

  @Test
  @DisplayName("bill paid from a confirmed ComBank attempt: recorded as that bank's attempt, and its SMS pairs with it")
  void billFromAttempt() throws Exception {
    UUID user = consented();
    String bill = rent(user, today);
    String id = paid(user, bill, ",\"originBankCode\":\"7056\"").path("payment").path("transaction").path("id").asText();
    assertEquals(1, sms(user, key(), "7010", "ACCOUNT_TXN", "58000.00").path("accepted").asInt(), "another bank");
    assertEquals(id, sms(user, key(), "7056", "ACCOUNT_TXN", "58000.00").path("results").get(0).path("transactionId").asText());
    assertEquals(2, ledger(user).path("page").path("totalElements").asInt());
  }
}
