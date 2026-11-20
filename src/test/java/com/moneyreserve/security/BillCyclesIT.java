package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiBillCycle;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillCycleStatus;
import com.moneyreserve.domain.BillStatus;
import com.moneyreserve.repository.BiBillCycleRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.BillService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bill cycles (V1.26.0): each due date of a bill is settled once, by one payment, however many
 * requests arrive together; an unanswered one becomes "Payment not confirmed" and can still be
 * answered; a payment pairs with a bank SMS only when the link is verified.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Bill cycles: settled once, never lost")
class BillCyclesIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiBillCycleRepository cycles;
  @Autowired private BiUserBillRepository userBills;
  @Autowired private BillService billService;
  @Autowired private TransactionTemplate tx;
  @Autowired private ObjectMapper json;

  private static final String API = "/api/insights";
  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));
  private UUID transfers;
  private UUID dining;

  @BeforeEach
  void seed() {
    transfers = ensure("TRANSFERS", "Transfers");
    ensure("BILLS_UTILITIES", "Bills & utilities");
    ensure(BiCategory.OTHER_CODE, "Other");
    dining = ensure("DINING_QA", "Dining");
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
        call(user, HttpMethod.POST, "/consent", "{\"consentVersion\":\"1.0\",\"policyHash\":\"cyc\",\"channel\":\"ANDROID\"}");
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return user;
  }

  private String bill(UUID user, String amount, LocalDate due) throws Exception {
    ResponseEntity<String> r =
        call(user, HttpMethod.POST, "/bills",
            "{\"name\":\"House rent\",\"expectedAmount\":" + amount + ",\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + due + "\"}");
    assertEquals(201, r.getStatusCode().value(), r.getBody());
    return data(r).path("id").asText();
  }

  private ResponseEntity<String> pay(UUID user, String billId, String clientRef, String extra) {
    return call(user, HttpMethod.POST, "/bills/" + billId + "/paid",
        "{\"clientRef\":\"" + clientRef + "\"" + (extra == null ? "" : "," + extra) + "}");
  }

  private JsonNode paid(UUID user, String billId, String extra) throws Exception {
    ResponseEntity<String> r = pay(user, billId, "bill-" + UUID.randomUUID(), extra);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private JsonNode detail(UUID user, String billId) throws Exception {
    ResponseEntity<String> r = call(user, HttpMethod.GET, "/bills/" + billId, null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private JsonNode cycleDue(JsonNode detail, LocalDate due) {
    for (JsonNode c : detail.path("cycles")) {
      if (due.toString().equals(c.path("dueOn").asText())) {
        return c;
      }
    }
    throw new AssertionError("no cycle due " + due + " in " + detail.path("cycles"));
  }

  private JsonNode sms(UUID user, String bank, String amount, String merchant, LocalDate date) throws Exception {
    String body =
        "{\"deviceId\":\"dev-cyc\",\"platform\":\"ANDROID\",\"messages\":[{\"idempotencyKey\":\"sms-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
            + "\",\"bankCode\":\"" + bank + "\",\"messageType\":\"ACCOUNT_TXN\",\"parsedAt\":\"" + OffsetDateTime.now()
            + "\",\"amount\":\"" + amount + "\",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"" + merchant
            + "\",\"txnDate\":\"" + date + "\",\"instrumentType\":\"BANK\"}]}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/messages/ingest", body);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private JsonNode confirmAttempt(UUID user, String bank, String amount) throws Exception {
    String body =
        "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Transfer to Other Banks\","
            + "\"txnDate\":\"" + today + "\",\"categoryId\":\"" + transfers + "\",\"clientRef\":\"att-" + UUID.randomUUID()
            + "\",\"origin\":\"ATTEMPT\",\"originBankCode\":\"" + bank + "\"}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/transactions", body);
    assertTrue(r.getStatusCode().is2xxSuccessful(), r.getBody());
    return data(r);
  }

  private int rows(UUID user) throws Exception {
    ResponseEntity<String> r =
        call(user, HttpMethod.GET, "/transactions?from=" + today.minusMonths(3) + "&to=" + today.plusDays(1), null);
    return data(r).path("page").path("totalElements").asInt();
  }

  private List<BiBillCycle> cyclesOf(String billId) {
    return cycles.findAllByUserBillIdOrderByDueOnDesc(UUID.fromString(billId));
  }

  /** The same request from {@code n} threads released together. */
  private List<ResponseEntity<String>> together(int n, java.util.function.IntFunction<ResponseEntity<String>> request)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    try {
      List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        int index = i;
        Callable<ResponseEntity<String>> task =
            () -> {
              go.await();
              return request.apply(index);
            };
        futures.add(pool.submit(task));
      }
      go.countDown();
      List<ResponseEntity<String>> out = new ArrayList<>();
      for (Future<ResponseEntity<String>> f : futures) {
        out.add(f.get(30, TimeUnit.SECONDS));
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }

  // ---- concurrent settlement and retries -----------------------------------------------------

  @Test
  @DisplayName("two 'Paid' taps with different clientRefs at the same moment: one payment, one paid cycle, the bill moves on once")
  void concurrentDifferentClientRefs() throws Exception {
    UUID user = consented();
    String bill = bill(user, "58000", today);
    List<ResponseEntity<String>> answers =
        together(4, i -> pay(user, bill, "tap-" + i + "-" + UUID.randomUUID(), "\"dueOn\":\"" + today + "\""));
    int recorded = 0;
    int already = 0;
    for (ResponseEntity<String> r : answers) {
      assertEquals(200, r.getStatusCode().value(), r.getBody());
      JsonNode d = data(r);
      if (d.path("alreadyPaid").asBoolean()) {
        already++;
      } else {
        recorded++;
      }
    }
    assertEquals(1, recorded);
    assertEquals(3, already);
    assertEquals(1, rows(user), "one payment");
    List<BiBillCycle> own = cyclesOf(bill);
    assertEquals(2, own.size(), "this cycle and the next");
    BiBillCycle paidCycle = own.stream().filter(c -> c.getDueOn().equals(today)).findFirst().orElseThrow();
    assertEquals(BillCycleStatus.PAID, paidCycle.getStatus());
    assertTrue(paidCycle.getTransactionId() != null);
    assertEquals(today.plusMonths(1), userBills.findById(UUID.fromString(bill)).orElseThrow().getNextDueOn(), "moved on once");
  }

  @Test
  @DisplayName("an older app (no due date) tapping 'Paid' twice at once: still one payment")
  void concurrentOlderApp() throws Exception {
    UUID user = consented();
    String bill = bill(user, "58000", today);
    List<ResponseEntity<String>> answers = together(3, i -> pay(user, bill, "old-" + i + "-" + UUID.randomUUID(), null));
    for (ResponseEntity<String> r : answers) {
      assertEquals(200, r.getStatusCode().value(), r.getBody());
    }
    assertEquals(1, rows(user));
    assertEquals(today.plusMonths(1), userBills.findById(UUID.fromString(bill)).orElseThrow().getNextDueOn());
  }

  @Test
  @DisplayName("the same clientRef retried (one after another, and at the same moment): one payment")
  void retriesSameClientRef() throws Exception {
    UUID user = consented();
    String bill = bill(user, "2500", today);
    String ref = "bill-" + bill + "-" + today;
    String due = "\"dueOn\":\"" + today + "\"";
    List<ResponseEntity<String>> answers = together(3, i -> pay(user, bill, ref, due));
    for (ResponseEntity<String> r : answers) {
      assertEquals(200, r.getStatusCode().value(), r.getBody());
    }
    JsonNode again = data(pay(user, bill, ref, due));
    assertTrue(again.path("alreadyPaid").asBoolean());
    assertEquals(1, rows(user));
  }

  @Test
  @DisplayName("a stale screen (the due date already paid) gets 'already paid', never next month's cycle")
  void staleScreen() throws Exception {
    UUID user = consented();
    String bill = bill(user, "2500", today);
    paid(user, bill, "\"dueOn\":\"" + today + "\"");
    JsonNode stale = paid(user, bill, "\"dueOn\":\"" + today + "\"");
    assertTrue(stale.path("alreadyPaid").asBoolean());
    assertEquals(today.plusMonths(1).toString(), stale.path("bill").path("nextDueOn").asText());
    BiBillCycle next = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today.plusMonths(1)).orElseThrow();
    assertEquals(BillCycleStatus.DUE, next.getStatus(), "next month untouched");
    assertEquals(1, rows(user));
  }

  // ---- matching: verified links only ---------------------------------------------------------

  @Test
  @DisplayName("confirmed in the sheet (from a ComBank attempt), then ComBank's SMS two days later: linked, one expense; the cycle points at the SMS")
  void lateSmsLinks() throws Exception {
    UUID user = consented();
    String bill = bill(user, "58000", today.minusDays(2));
    paid(user, bill, "\"originBankCode\":\"7056\",\"paidOn\":\"" + today.minusDays(2) + "\"");
    JsonNode in = sms(user, "7056", "58000.00", "Online Transfer", today);
    JsonNode item = in.path("results").get(0);
    assertTrue(item.path("billMatch").path("linked").asBoolean(), in.toString());
    assertEquals("House rent", item.path("billMatch").path("billName").asText());
    assertEquals(1, rows(user), "one expense");
    BiBillCycle c = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today.minusDays(2)).orElseThrow();
    assertEquals(UUID.fromString(item.path("transactionId").asText()), c.getTransactionId());
    assertEquals(today, c.getPaidOn(), "the bank's date");
  }

  @Test
  @DisplayName("confirmed from the reminder (no bank), then the SMS: linked; Undo makes the SMS its own transaction again and the bill keeps the user's payment")
  void confirmedThenSmsThenUndo() throws Exception {
    UUID user = consented();
    String bill = bill(user, "58000", today);
    String recorded = paid(user, bill, "\"dueOn\":\"" + today + "\"").path("payment").path("transaction").path("id").asText();
    JsonNode match = sms(user, "7010", "58000.00", "Online Transfer", today).path("results").get(0).path("billMatch");
    assertTrue(match.path("linked").asBoolean());
    assertEquals(recorded, match.path("recordedTransactionId").asText());
    assertEquals(1, rows(user));
    String smsId = match.path("smsTransactionId").asText();

    ResponseEntity<String> undo =
        call(user, HttpMethod.POST, "/bills/" + bill + "/unmatch", "{\"smsTransactionId\":\"" + smsId + "\"}");
    assertEquals(200, undo.getStatusCode().value(), undo.getBody());
    assertEquals(2, rows(user), "two payments again");
    BiBillCycle c = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today).orElseThrow();
    assertEquals(BillCycleStatus.PAID, c.getStatus());
    assertEquals(UUID.fromString(recorded), c.getTransactionId(), "the bill keeps the user's payment");
    assertEquals(400, call(user, HttpMethod.POST, "/bills/" + bill + "/unmatch", "{\"smsTransactionId\":\"" + smsId + "\"}").getStatusCode().value(), "undo once");
  }

  @Test
  @DisplayName("the SMS came before the answer: 'Paid' in the sheet makes that SMS the payment, nothing added")
  void smsBeforeAnswer() throws Exception {
    UUID user = consented();
    String smsId = sms(user, "7010", "2500.00", "Online Transfer", today).path("results").get(0).path("transactionId").asText();
    String bill = bill(user, "2500", today);
    JsonNode p = paid(user, bill, "\"dueOn\":\"" + today + "\"");
    assertTrue(p.path("payment").path("duplicate").asBoolean());
    assertEquals(smsId, p.path("payment").path("transaction").path("id").asText());
    assertEquals(1, rows(user));
    assertEquals(UUID.fromString(smsId), cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today).orElseThrow().getTransactionId());
  }

  @Test
  @DisplayName("two bills of the same amount both confirmed, one SMS: not linked, asked once; 'Yes' links it to that bill")
  void twoCandidatesAsk() throws Exception {
    UUID user = consented();
    String rent = bill(user, "2500", today);
    ResponseEntity<String> r =
        call(user, HttpMethod.POST, "/bills",
            "{\"name\":\"Internet\",\"expectedAmount\":2500,\"cadence\":\"MONTHLY\",\"nextDueOn\":\"" + today + "\"}");
    String internet = data(r).path("id").asText();
    paid(user, rent, "\"dueOn\":\"" + today + "\"");
    String internetPaid = paid(user, internet, "\"dueOn\":\"" + today + "\"").path("payment").path("transaction").path("id").asText();
    JsonNode match = sms(user, "7010", "2500.00", "Online Transfer", today).path("results").get(0).path("billMatch");
    assertFalse(match.path("linked").asBoolean(), "could be either: ask, never guess");
    assertEquals(3, rows(user));

    String body = "{\"smsTransactionId\":\"" + match.path("smsTransactionId").asText() + "\",\"recordedTransactionId\":\"" + internetPaid + "\"}";
    ResponseEntity<String> yes = call(user, HttpMethod.POST, "/bills/" + internet + "/match", body);
    assertEquals(200, yes.getStatusCode().value(), yes.getBody());
    assertEquals(2, rows(user));
    assertEquals(
        UUID.fromString(match.path("smsTransactionId").asText()),
        cycles.findByUserBillIdAndDueOn(UUID.fromString(internet), today).orElseThrow().getTransactionId());
    assertEquals(400, call(user, HttpMethod.POST, "/bills/" + internet + "/match", body).getStatusCode().value(), "once");
  }

  @Test
  @DisplayName("an amount off the bill's (a bill added by hand: exact): asked, not linked; far off: a different payment, not even asked")
  void variableAmountAsks() throws Exception {
    UUID user = consented();
    String bill = bill(user, "2500", today);
    paid(user, bill, "\"dueOn\":\"" + today + "\"");
    assertTrue(sms(user, "7010", "9000.00", "Shop", today).path("results").get(0).path("billMatch").isMissingNode(), "far off");
    JsonNode match = sms(user, "7010", "2900.00", "Online Transfer", today).path("results").get(0).path("billMatch");
    assertFalse(match.path("linked").asBoolean(), match.toString());
    assertEquals("2900.00", match.path("amount").asText());
    assertEquals(3, rows(user));
  }

  @Test
  @DisplayName("'Paid' in the sheet when a stored SMS is close but not certain: recorded, and the SMS comes back to ask about")
  void paidWithPossibleSms() throws Exception {
    UUID user = consented();
    String smsId = sms(user, "7010", "2900.00", "Online Transfer", today).path("results").get(0).path("transactionId").asText();
    String bill = bill(user, "2500", today);
    JsonNode p = paid(user, bill, "\"dueOn\":\"" + today + "\"");
    assertFalse(p.path("payment").path("duplicate").asBoolean());
    assertEquals(smsId, p.path("possibleSms").path("smsTransactionId").asText());
    assertFalse(p.path("possibleSms").path("linked").asBoolean());
    assertEquals(2, rows(user));
  }

  @Test
  @DisplayName("ambiguous attempts (no bill): two identical confirmed attempts and one SMS, or two SMS and one confirmation: nothing is paired")
  void ambiguousLeftUnpaired() throws Exception {
    UUID user = consented();
    confirmAttempt(user, "7056", "500.00");
    confirmAttempt(user, "7056", "500.00");
    assertEquals(1, sms(user, "7056", "500.00", "Online Transfer", today).path("accepted").asInt(), "two could be it");

    UUID other = consented();
    sms(other, "7056", "750.00", "Online Transfer", today);
    sms(other, "7056", "750.00", "Online Transfer", today);
    JsonNode c = confirmAttempt(other, "7056", "750.00");
    assertFalse(c.path("duplicate").asBoolean(), "two stored SMS could be it");
    assertEquals(3, rows(other));
  }

  @Test
  @DisplayName("an attempt with no bill pairs only with the same bank: an SMS from another bank is never paired on the amount alone")
  void attemptOtherBankNeverPairs() throws Exception {
    UUID user = consented();
    confirmAttempt(user, "7056", "6800.00");
    assertEquals(1, sms(user, "7010", "6800.00", "Online Transfer", today).path("accepted").asInt());
    assertEquals(2, rows(user));
  }

  // ---- rollover and later answers ------------------------------------------------------------

  @Test
  @DisplayName("10 days after the due date with no answer: Payment not confirmed, the bill moves on; answered later it is paid once")
  void rollOverThenAnswerLater() throws Exception {
    UUID user = consented();
    LocalDate due = today.minusDays(12);
    String bill = bill(user, "58000", due);
    JsonNode list = data(call(user, HttpMethod.GET, "/bills", null));
    JsonNode row = list.path("bills").get(0);
    LocalDate next = due.plusMonths(1);
    assertEquals(next.toString(), row.path("nextDueOn").asText(), "moved on one cycle");
    assertEquals(1, row.path("unconfirmedCycles").asInt());
    JsonNode d = detail(user, bill);
    JsonNode missed = cycleDue(d, due);
    assertEquals("NOT_CONFIRMED", missed.path("status").asText());
    assertEquals("DUE", cycleDue(d, next).path("status").asText());

    // Answered later: the amount and date the customer gives; the current cycle is not touched.
    String cycleId = missed.path("id").asText();
    String ref = "cycle-" + cycleId;
    String body = "\"cycleId\":\"" + cycleId + "\",\"amount\":57500,\"paidOn\":\"" + due.plusDays(1) + "\"";
    JsonNode p = data(pay(user, bill, ref, body));
    assertFalse(p.path("alreadyPaid").asBoolean(), p.toString());
    assertEquals(next.toString(), p.path("bill").path("nextDueOn").asText(), "the current due date stays");
    assertTrue(data(pay(user, bill, ref, body)).path("alreadyPaid").asBoolean(), "a retry is not a second payment");
    JsonNode after = cycleDue(detail(user, bill), due);
    assertEquals("PAID", after.path("status").asText());
    assertEquals("57500.00", after.path("actualAmount").asText());
    assertEquals("58000.00", after.path("expectedAmount").asText(), "expected amount kept as the customer set it");
    assertEquals(due.plusDays(1).toString(), after.path("paidOn").asText());
    assertEquals(1, rows(user));
    assertTrue(data(call(user, HttpMethod.GET, "/bills", null)).path("bills").get(0).path("unconfirmedCycles").isMissingNode());
  }

  @Test
  @DisplayName("several due dates missed: each its own Payment not confirmed (none skipped, none invented as paid); 'Didn't pay' only for those")
  void severalMissedAndSkip() throws Exception {
    UUID user = consented();
    LocalDate first = today.minusDays(45);
    String bill = bill(user, "2500", first);
    data(call(user, HttpMethod.GET, "/bills", null));
    List<BiBillCycle> own = cyclesOf(bill);
    long notConfirmed = own.stream().filter(c -> c.getStatus() == BillCycleStatus.NOT_CONFIRMED).count();
    assertTrue(notConfirmed >= 1);
    assertEquals(0, own.stream().filter(c -> c.getStatus() == BillCycleStatus.PAID).count(), "nothing assumed paid");
    LocalDate current = userBills.findById(UUID.fromString(bill)).orElseThrow().getNextDueOn();
    assertFalse(today.isAfter(current.plusDays(10)), "the current due date is still being asked about");
    for (LocalDate d = first; d.isBefore(current); d = d.plusMonths(1)) {
      LocalDate due = d;
      assertTrue(own.stream().anyMatch(c -> c.getDueOn().equals(due) && c.getStatus() == BillCycleStatus.NOT_CONFIRMED), "due " + d);
    }

    BiBillCycle missed = own.stream().filter(c -> c.getStatus() == BillCycleStatus.NOT_CONFIRMED).findFirst().orElseThrow();
    ResponseEntity<String> skip = call(user, HttpMethod.POST, "/bills/" + bill + "/cycles/" + missed.getId() + "/skip", null);
    assertEquals(200, skip.getStatusCode().value(), skip.getBody());
    assertEquals(BillCycleStatus.SKIPPED, cycles.findById(missed.getId()).orElseThrow().getStatus());
    BiBillCycle due = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), current).orElseThrow();
    assertEquals(400, call(user, HttpMethod.POST, "/bills/" + bill + "/cycles/" + due.getId() + "/skip", null).getStatusCode().value());
    // A skipped cycle is answered: paying it is "already paid", nothing recorded.
    assertTrue(paid(user, bill, "\"cycleId\":\"" + missed.getId() + "\"").path("alreadyPaid").asBoolean());
    assertEquals(0, rows(user));
  }

  @Test
  @DisplayName("the daily refresh after the asking days: the current cycle of a bill becomes Payment not confirmed")
  void refreshLater() throws Exception {
    UUID user = consented();
    String bill = bill(user, "2500", today);
    tx.executeWithoutResult(s -> billService.refresh(user, today.plusDays(11)));
    assertEquals(BillCycleStatus.NOT_CONFIRMED, cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today).orElseThrow().getStatus());
    assertEquals(today.plusMonths(1), userBills.findById(UUID.fromString(bill)).orElseThrow().getNextDueOn());
    // Within the asking days nothing changes.
    UUID other = consented();
    String b2 = bill(other, "2500", today);
    tx.executeWithoutResult(s -> billService.refresh(other, today.plusDays(10)));
    assertEquals(BillCycleStatus.DUE, cycles.findByUserBillIdAndDueOn(UUID.fromString(b2), today).orElseThrow().getStatus());
    assertEquals(today, userBills.findById(UUID.fromString(b2)).orElseThrow().getNextDueOn());
  }

  @Test
  @DisplayName("a found bill whose payment SMS arrives after it was Payment not confirmed: that cycle becomes paid")
  void lateSmsSettlesFoundBill() throws Exception {
    UUID user = consented();
    // Three monthly Netflix payments make a found bill; its next due date has passed unpaid.
    LocalDate last = today.minusDays(45);
    for (int i = 2; i >= 0; i--) {
      sms(user, "7010", "1490.00", "Netflix", last.minusMonths(i));
    }
    data(call(user, HttpMethod.GET, "/bills", null));
    // A bill found before suggestions (V1.27.0): DETECTED, as existing ones stay.
    for (BiUserBill b : userBills.findAllByUserIdOrderByNextDueOnAsc(user)) {
      if (b.getStatus() == BillStatus.SUGGESTED) {
        b.setStatus(BillStatus.DETECTED);
        userBills.save(b);
      }
    }
    JsonNode list = data(call(user, HttpMethod.GET, "/bills", null));
    JsonNode found = null;
    for (JsonNode b : list.path("bills")) {
      if (!"MANUAL".equals(b.path("status").asText())) {
        found = b;
      }
    }
    assertTrue(found != null, list.toString());
    String billId = found.path("id").asText();
    LocalDate due = LocalDate.parse(found.path("nextDueOn").asText());
    assertTrue(today.isAfter(due.plusDays(10)), "overdue by more than the asking days");
    assertEquals(BillCycleStatus.NOT_CONFIRMED, cycles.findByUserBillIdAndDueOn(UUID.fromString(billId), due).orElseThrow().getStatus());

    // The late SMS for that due date.
    sms(user, "7010", "1490.00", "Netflix", due.plusDays(2));
    data(call(user, HttpMethod.GET, "/bills", null));
    BiBillCycle settled = cycles.findByUserBillIdAndDueOn(UUID.fromString(billId), due).orElseThrow();
    assertEquals(BillCycleStatus.PAID, settled.getStatus());
    assertEquals(due.plusDays(2), settled.getPaidOn());
    assertEquals(BiBillCycle.FOUND, settled.getSettledBy());
  }

  // ---- payments removed or corrected ---------------------------------------------------------

  @Test
  @DisplayName("the payment that settled a cycle is deleted: the cycle can be answered again; corrected: the cycle shows the new amount")
  void deleteReopensEditUpdates() throws Exception {
    UUID user = consented();
    String bill = bill(user, "2500", today);
    String txn = paid(user, bill, "\"dueOn\":\"" + today + "\"").path("payment").path("transaction").path("id").asText();
    String range = "?from=" + today.withDayOfMonth(1) + "&to=" + today.plusDays(1);

    ResponseEntity<String> edit = call(user, HttpMethod.PATCH, "/transactions/" + txn + range, "{\"amount\":2650}");
    assertEquals(200, edit.getStatusCode().value(), edit.getBody());
    BiBillCycle c = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today).orElseThrow();
    assertEquals(0, new BigDecimal("2650.00").compareTo(c.getActualAmount()));

    assertEquals(200, call(user, HttpMethod.DELETE, "/transactions/" + txn + range, null).getStatusCode().value());
    c = cycles.findByUserBillIdAndDueOn(UUID.fromString(bill), today).orElseThrow();
    assertEquals(BillCycleStatus.NOT_CONFIRMED, c.getStatus(), "the bill already moved on: answerable from its history");
    assertTrue(c.getTransactionId() == null);
    JsonNode again = paid(user, bill, "\"cycleId\":\"" + c.getId() + "\"");
    assertFalse(again.path("alreadyPaid").asBoolean());
    assertEquals(1, rows(user));
  }

  // ---- Cash Wallet -----------------------------------------------------------------------------

  @Test
  @DisplayName("bill payments (one, retried, concurrent) never move the Cash Wallet; a cash expense still does, once")
  void cashWalletUntouchedByBills() throws Exception {
    UUID user = consented();
    String version = data(call(user, HttpMethod.GET, "/cash-wallet?currency=LKR", null)).path("version").asText();
    ResponseEntity<String> open =
        call(user, HttpMethod.POST, "/cash-wallet/reconcile",
            "{\"currency\":\"LKR\",\"clientRef\":\"open-" + UUID.randomUUID() + "\",\"expectedVersion\":\"" + version
                + "\",\"actual\":\"10000.00\",\"reason\":\"CORRECTION\"}");
    assertEquals(200, open.getStatusCode().value(), open.getBody());

    String bill = bill(user, "2500", today);
    together(3, i -> pay(user, bill, "w-" + i + "-" + UUID.randomUUID(), "\"dueOn\":\"" + today + "\""));
    pay(user, bill, "w-retry", "\"dueOn\":\"" + today + "\"");
    pay(user, bill, "w-retry", "\"dueOn\":\"" + today + "\"");
    assertEquals("10000.00", data(call(user, HttpMethod.GET, "/cash-wallet?currency=LKR", null)).path("balance").asText());

    String lunch =
        "{\"amount\":700,\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Lunch\",\"txnDate\":\"" + today
            + "\",\"categoryId\":\"" + dining + "\",\"clientRef\":\"lunch-" + UUID.randomUUID() + "\",\"paymentSource\":\"CASH_WALLET\"}";
    assertEquals(201, call(user, HttpMethod.POST, "/transactions", lunch).getStatusCode().value());
    assertEquals("9300.00", data(call(user, HttpMethod.GET, "/cash-wallet?currency=LKR", null)).path("balance").asText());
  }
}
