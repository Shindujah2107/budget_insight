package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiCashWalletEntryRepository;
import com.moneyreserve.repository.BiCashWalletRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The cash wallet end to end over HTTP (V1.21.0): explicit opening, withdrawals in without
 * spending, cash expenses out exactly once, edits/deletes/source changes, every reconciliation
 * reason, retries, stale versions, insufficient funds and two saves racing for the same cash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Cash wallet")
class CashWalletIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiAccountRepository accounts;
  @Autowired private BiCashWalletRepository wallets;
  @Autowired private BiCashWalletEntryRepository entries;
  @Autowired private com.moneyreserve.service.impl.CashWalletService cashService;
  @Autowired private com.moneyreserve.repository.BiTransactionRepository txns;
  @Autowired private org.springframework.transaction.PlatformTransactionManager txManager;

  private static final String API = "/api/insights";
  private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Colombo"));
  private UUID dining;
  private UUID cashWithdrawal;

  @BeforeEach
  void seed() {
    ensure(BiCategory.OTHER_CODE);
    ensure("TRANSFERS");
    dining = ensure("DINING_OUT");
    cashWithdrawal = ensure(BiCategory.CASH_WITHDRAWAL_CODE);
  }

  private UUID ensure(String code) {
    return categories
        .findByCodeAndIsActiveTrue(code)
        .map(BiCategory::getId)
        .orElseGet(() -> categories.save(BiCategory.builder().id(UUID.randomUUID()).code(code).nameEn(code)
            .iconKey("ic_other").sortOrder((short) 1).isActive(true).createdAt(OffsetDateTime.now()).build()).getId());
  }

  // ---------------------------------------------------------------------------------------------

  private HttpEntity<String> as(UUID user, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(user));
    h.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, h);
  }

  private ResponseEntity<String> call(UUID user, HttpMethod method, String path, String body) {
    return rest.exchange(API + path, method, as(user, body), String.class);
  }

  private JsonNode data(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("data");
  }

  private String error(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("error").asText();
  }

  private JsonNode wallet(UUID user) throws Exception {
    ResponseEntity<String> r = call(user, HttpMethod.GET, "/cash-wallet?currency=LKR", null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private ResponseEntity<String> reconcile(UUID user, String ref, String version, String actual, String extra) {
    String body = "{\"currency\":\"LKR\",\"clientRef\":\"" + ref + "\",\"expectedVersion\":\"" + version
        + "\",\"actual\":\"" + actual + "\"" + (extra == null ? "" : "," + extra) + "}";
    return call(user, HttpMethod.POST, "/cash-wallet/reconcile", body);
  }

  private JsonNode open(UUID user, String amount) throws Exception {
    ResponseEntity<String> r = reconcile(user, "open-" + UUID.randomUUID(), wallet(user).path("version").asText(),
        amount, "\"reason\":\"CORRECTION\"");
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return data(r);
  }

  private ResponseEntity<String> spend(UUID user, String ref, String amount, UUID category, String extra) {
    String body = "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Lunch\","
        + "\"txnDate\":\"" + today + "\",\"categoryId\":\"" + category + "\",\"clientRef\":\"" + ref + "\""
        + (extra == null ? "" : "," + extra) + "}";
    return call(user, HttpMethod.POST, "/transactions", body);
  }

  private String balance(UUID user) throws Exception {
    return wallet(user).path("balance").asText();
  }

  private BigDecimal spent(UUID user) throws Exception {
    String month = "?from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
    return new BigDecimal(data(call(user, HttpMethod.GET, "/breakdown" + month, null)).path("total").asText("0"));
  }

  private JsonNode totals(UUID user) throws Exception {
    String month = "?from=" + today.withDayOfMonth(1) + "&to=" + today.withDayOfMonth(today.lengthOfMonth());
    return data(call(user, HttpMethod.GET, "/transactions" + month, null)).path("totals");
  }

  private UUID account(UUID user) {
    return accounts.save(BiAccount.builder().id(UUID.randomUUID()).userId(user).bankCode("7056")
        .instrumentType(InstrumentType.BANK).maskedTail("1234").isActive(true).createdAt(OffsetDateTime.now()).build()).getId();
  }

  private static int cmp(String a, String b) {
    return new BigDecimal(a).compareTo(new BigDecimal(b));
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("inactive until opened; nothing historical is counted; a wallet payment is refused before then")
  void openingIsExplicit() throws Exception {
    UUID user = UUID.randomUUID();
    JsonNode before = wallet(user);
    assertFalse(before.path("active").asBoolean());
    assertEquals("0.00", before.path("balance").asText());
    assertTrue(before.has("reviewDueAt") && before.path("reviewDueAt").isNull());

    ResponseEntity<String> early = spend(user, "early-1-" + UUID.randomUUID(), "100.00", dining, "\"paymentSource\":\"CASH_WALLET\"");
    assertEquals(409, early.getStatusCode().value());
    assertEquals("CASH_NOT_ACTIVE", error(early));
    assertEquals(0, totals(user).path("moneyOutCount").asInt(), "nothing saved");

    // A withdrawal from before the wallet existed stays out of it.
    spend(user, "pre-" + UUID.randomUUID(), "5000.00", cashWithdrawal, null);
    JsonNode opened = open(user, "0");
    assertTrue(opened.path("active").asBoolean());
    assertEquals("0.00", opened.path("balance").asText(), "zero is a valid opening amount");
    assertFalse(opened.path("activatedAt").isNull());
  }

  @Test
  @DisplayName("a withdrawal adds cash without spending; a cash expense takes it out and is spent exactly once")
  void withdrawalAndExpense() throws Exception {
    UUID user = UUID.randomUUID();
    open(user, "1000.00");
    assertEquals(0, cmp("0", spent(user).toPlainString()), "opening cash is not income or spending");
    assertEquals(0, cmp("0", totals(user).path("moneyIn").asText()));

    String atm = "atm-" + UUID.randomUUID();
    assertEquals(201, spend(user, atm, "8000.00", cashWithdrawal, null).getStatusCode().value());
    spend(user, atm, "8000.00", cashWithdrawal, null); // retried: one transaction, one credit
    JsonNode w = wallet(user);
    assertEquals("9000.00", w.path("balance").asText());
    assertFalse(w.path("reviewDueAt").isNull(), "a withdrawal asks for a check in seven days");
    assertEquals(0, spent(user).signum(), "a withdrawal is not spending");

    // A withdrawal dated before the opening day, imported now, adds nothing.
    String old = "{\"amount\":3000.00,\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"ATM\",\"txnDate\":\""
        + today.minusDays(3) + "\",\"categoryId\":\"" + cashWithdrawal + "\",\"clientRef\":\"old-" + UUID.randomUUID() + "\"}";
    call(user, HttpMethod.POST, "/transactions", old);
    assertEquals("9000.00", balance(user));

    String ref = "lunch-" + UUID.randomUUID();
    ResponseEntity<String> saved = spend(user, ref, "1500.00", dining, "\"paymentSource\":\"CASH_WALLET\"");
    assertEquals(201, saved.getStatusCode().value(), saved.getBody());
    assertEquals("CASH_WALLET", data(saved).path("transaction").path("paymentSource").asText());
    ResponseEntity<String> retry = spend(user, ref, "1500.00", dining, "\"paymentSource\":\"CASH_WALLET\"");
    assertTrue(data(retry).path("duplicate").asBoolean());
    assertEquals("7500.00", balance(user));
    assertEquals(0, cmp("1500.00", spent(user).toPlainString()), "counted once in spending");

    // An older app's cash entry (no account, no paymentSource) is paid from the active wallet too.
    spend(user, "legacy-" + UUID.randomUUID(), "500.00", dining, null);
    assertEquals("7000.00", balance(user));
    assertEquals(0, cmp("2000.00", spent(user).toPlainString()));

    JsonNode activity = wallet(user).path("recentActivity");
    assertEquals(4, activity.size(), "opening, withdrawal and two cash expenses");
  }

  @Test
  @DisplayName("insufficient cash or a withdrawal category is refused with nothing saved")
  void refusals() throws Exception {
    UUID user = UUID.randomUUID();
    open(user, "100.00");
    String version = wallet(user).path("version").asText();
    ResponseEntity<String> tooMuch = spend(user, "big-" + UUID.randomUUID(), "100.01", dining, "\"paymentSource\":\"CASH_WALLET\"");
    assertEquals(409, tooMuch.getStatusCode().value());
    assertEquals("CASH_INSUFFICIENT", error(tooMuch));
    ResponseEntity<String> withdrawal = spend(user, "wd-" + UUID.randomUUID(), "10.00", cashWithdrawal, "\"paymentSource\":\"CASH_WALLET\"");
    assertEquals(422, withdrawal.getStatusCode().value());
    assertEquals("CASH_INVALID", error(withdrawal));
    assertEquals("100.00", balance(user));
    assertEquals(version, wallet(user).path("version").asText(), "a refusal changes nothing");
    assertEquals(0, totals(user).path("moneyOutCount").asInt());
  }

  @Test
  @DisplayName("edits, a payment-source change and a delete move the wallet once each")
  void editsAndDeletes() throws Exception {
    UUID user = UUID.randomUUID();
    open(user, "5000.00");
    String id = data(spend(user, "e-" + UUID.randomUUID(), "1500.00", dining, "\"paymentSource\":\"CASH_WALLET\""))
        .path("transaction").path("id").asText();
    assertEquals("3500.00", balance(user));

    assertEquals(200, call(user, HttpMethod.PATCH, "/transactions/" + id, "{\"amount\":2000.00}").getStatusCode().value());
    assertEquals("3000.00", balance(user));
    ResponseEntity<String> tooMuch = call(user, HttpMethod.PATCH, "/transactions/" + id, "{\"amount\":9000.00}");
    assertEquals(409, tooMuch.getStatusCode().value());
    assertEquals("3000.00", balance(user), "the refused edit changed nothing");

    UUID acct = account(user);
    call(user, HttpMethod.PATCH, "/transactions/" + id, "{\"paymentSource\":\"ACCOUNT\",\"accountId\":\"" + acct + "\"}");
    assertEquals("5000.00", balance(user), "paid by the bank instead: the cash comes back");
    call(user, HttpMethod.PATCH, "/transactions/" + id, "{\"paymentSource\":\"CASH_WALLET\"}");
    assertEquals("3000.00", balance(user));
    assertEquals(0, cmp("2000.00", spent(user).toPlainString()), "still one expense");

    assertEquals(200, call(user, HttpMethod.DELETE, "/transactions/" + id, null).getStatusCode().value());
    assertEquals("5000.00", balance(user));
    assertEquals(0, spent(user).signum());
  }

  @Test
  @DisplayName("reconcile: confirm, spent, income, correction, transfer and deposit; neutral where they should be")
  void reconcileReasons() throws Exception {
    UUID user = UUID.randomUUID();
    UUID acct = account(user);
    open(user, "1000.00");
    spend(user, "atm-" + UUID.randomUUID(), "4000.00", cashWithdrawal, null);
    JsonNode w = wallet(user);
    assertEquals("5000.00", w.path("balance").asText());

    // Same amount: no transaction, checks answered, version moves.
    JsonNode confirmed = data(reconcile(user, "conf-" + UUID.randomUUID(), w.path("version").asText(), "5000.00", null));
    assertTrue(confirmed.path("reviewDueAt").isNull());
    assertFalse(confirmed.path("version").asText().equals(w.path("version").asText()));

    // Less: spent on dining (one expense, counted once).
    JsonNode spentNow = data(reconcile(user, "spent-" + UUID.randomUUID(), confirmed.path("version").asText(), "4200.00",
        "\"reason\":\"SPENT\",\"categoryId\":\"" + dining + "\""));
    assertEquals("4200.00", spentNow.path("balance").asText());
    assertEquals(0, cmp("800.00", spent(user).toPlainString()));

    // More: income (counted as money in, not spending).
    JsonNode income = data(reconcile(user, "inc-" + UUID.randomUUID(), spentNow.path("version").asText(), "4700.00",
        "\"reason\":\"INCOME\""));
    assertEquals("4700.00", income.path("balance").asText());
    assertEquals(0, cmp("500.00", totals(user).path("moneyIn").asText()));

    // Corrections and own-money movements: neither spending nor income.
    JsonNode up = data(reconcile(user, "cor-" + UUID.randomUUID(), income.path("version").asText(), "4800.00",
        "\"reason\":\"CORRECTION\""));
    JsonNode transfer = data(reconcile(user, "tr-" + UUID.randomUUID(), up.path("version").asText(), "6800.00",
        "\"reason\":\"TRANSFER\",\"accountId\":\"" + acct + "\""));
    assertEquals("6800.00", transfer.path("balance").asText());

    // A deposit linked to the customer's own imported bank credit: not duplicated, not income.
    String credit = "{\"amount\":1000.00,\"currency\":\"LKR\",\"direction\":\"CREDIT\",\"merchant\":\"CRM deposit\",\"txnDate\":\""
        + today + "\",\"categoryId\":\"" + dining + "\",\"accountId\":\"" + acct + "\",\"clientRef\":\"cr-" + UUID.randomUUID() + "\"}";
    String creditId = data(call(user, HttpMethod.POST, "/transactions", credit)).path("transaction").path("id").asText();
    assertEquals(0, cmp("1500.00", totals(user).path("moneyIn").asText()), "an unlinked credit is income as before");
    JsonNode deposit = data(reconcile(user, "dep-" + UUID.randomUUID(), transfer.path("version").asText(), "5800.00",
        "\"reason\":\"DEPOSIT\",\"accountId\":\"" + acct + "\",\"matchingTransactionId\":\"" + creditId + "\""));
    assertEquals("5800.00", deposit.path("balance").asText());
    assertEquals(0, cmp("500.00", totals(user).path("moneyIn").asText()), "linked: now a movement, not income");
    assertEquals(0, cmp("800.00", spent(user).toPlainString()), "spending untouched by movements");

    // Wrong direction for the difference is refused.
    ResponseEntity<String> wrong = reconcile(user, "wrong-" + UUID.randomUUID(), deposit.path("version").asText(), "6000.00",
        "\"reason\":\"SPENT\",\"categoryId\":\"" + dining + "\"");
    assertEquals(422, wrong.getStatusCode().value());
  }

  @Test
  @DisplayName("a retried reconcile applies once; a stale or reused reference changes nothing")
  void reconcileRetriesAndStale() throws Exception {
    UUID user = UUID.randomUUID();
    JsonNode opened = open(user, "2000.00");
    String version = opened.path("version").asText();
    String ref = "same-" + UUID.randomUUID();
    String extra = "\"reason\":\"SPENT\",\"categoryId\":\"" + dining + "\"";
    JsonNode first = data(reconcile(user, ref, version, "1500.00", extra));
    // The phone lost the answer and sends the identical request again, after something else moved the wallet.
    spend(user, "x-" + UUID.randomUUID(), "100.00", dining, "\"paymentSource\":\"CASH_WALLET\"");
    ResponseEntity<String> again = reconcile(user, ref, version, "1500.00", extra);
    assertEquals(200, again.getStatusCode().value(), again.getBody());
    assertEquals("1400.00", data(again).path("balance").asText(), "applied once, then the 100 expense");
    assertEquals(0, cmp("600.00", spent(user).toPlainString()));

    ResponseEntity<String> reused = reconcile(user, ref, version, "1000.00", extra);
    assertEquals(422, reused.getStatusCode().value());
    assertEquals("CASH_REFERENCE_REUSED", error(reused));

    ResponseEntity<String> stale = reconcile(user, "st-" + UUID.randomUUID(), first.path("version").asText(), "1000.00", extra);
    assertEquals(409, stale.getStatusCode().value());
    assertEquals("CASH_STALE", error(stale));
    assertEquals("1400.00", balance(user));
  }

  @Test
  @DisplayName("two cash expenses racing for the same cash: one is saved, the other refused")
  void concurrentSpendsCannotOverdraw() throws Exception {
    UUID user = UUID.randomUUID();
    open(user, "1000.00");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Callable<Integer>> jobs = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      String ref = "race-" + i + "-" + UUID.randomUUID();
      jobs.add(() -> spend(user, ref, "700.00", dining, "\"paymentSource\":\"CASH_WALLET\"").getStatusCode().value());
    }
    List<Integer> codes = new ArrayList<>();
    for (Future<Integer> f : pool.invokeAll(jobs)) {
      codes.add(f.get());
    }
    pool.shutdown();
    assertTrue(codes.contains(201) && codes.contains(409), codes.toString());
    assertEquals("300.00", balance(user));
    UUID walletId = wallets.findByUserIdAndCurrency(user, "LKR").orElseThrow().getId();
    assertEquals(0, entries.sumByWalletId(walletId).compareTo(new BigDecimal("300.00")), "ledger agrees with the balance");
  }

  @Test
  @DisplayName("another customer's account or transaction cannot be used")
  void ownership() throws Exception {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    UUID bobs = account(bob);
    JsonNode opened = open(alice, "100.00");
    ResponseEntity<String> r = reconcile(alice, "own-" + UUID.randomUUID(), opened.path("version").asText(), "200.00",
        "\"reason\":\"TRANSFER\",\"accountId\":\"" + bobs + "\"");
    assertEquals(422, r.getStatusCode().value());
    assertFalse(wallet(bob).path("active").asBoolean(), "bob's wallet untouched");
    assertEquals("100.00", balance(alice));
  }

  // ---------------------------------------------------------------------------------------------

  private String create(UUID user, String direction, String amount, UUID category, UUID account) throws Exception {
    String body = "{\"amount\":" + amount + ",\"currency\":\"LKR\",\"direction\":\"" + direction + "\",\"merchant\":\"ATM\","
        + "\"txnDate\":\"" + today + "\",\"categoryId\":\"" + category + "\",\"accountId\":\"" + account
        + "\",\"clientRef\":\"c-" + UUID.randomUUID() + "\"}";
    ResponseEntity<String> r = call(user, HttpMethod.POST, "/transactions", body);
    assertEquals(201, r.getStatusCode().value(), r.getBody());
    return data(r).path("transaction").path("id").asText();
  }

  /** What ingest does when a refund/reversal message arrives. */
  private void bankReversal(String txnId) {
    new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s ->
        cashService.linkBankReversal(txns.findById(UUID.fromString(txnId)).orElseThrow()));
  }

  @Test
  @DisplayName("opening that already counts today's withdrawals: one imported later, dated today, is not added again")
  void sameDayOpening() throws Exception {
    UUID user = UUID.randomUUID();
    String version = wallet(user).path("version").asText();
    ResponseEntity<String> opened = reconcile(user, "open-" + UUID.randomUUID(), version, "8000.00",
        "\"reason\":\"CORRECTION\",\"openingIncludesToday\":true");
    assertEquals(200, opened.getStatusCode().value(), opened.getBody());
    spend(user, "late-" + UUID.randomUUID(), "5000.00", cashWithdrawal, null);
    assertEquals("8000.00", balance(user), "already in the opening count");

    UUID other = UUID.randomUUID();
    open(other, "8000.00");
    spend(other, "late-" + UUID.randomUUID(), "5000.00", cashWithdrawal, null);
    assertEquals("13000.00", balance(other), "without the flag a same-day withdrawal still counts");
  }

  @Test
  @DisplayName("a bank reversal takes a withdrawal's cash back once; it is neither income nor spending")
  void bankReversalOfWithdrawal() throws Exception {
    UUID user = UUID.randomUUID();
    UUID acct = account(user);
    open(user, "1000.00");
    create(user, "DEBIT", "8000.00", cashWithdrawal, acct);
    assertEquals("9000.00", balance(user));

    String reversal = create(user, "CREDIT", "8000.00", dining, acct);
    bankReversal(reversal);
    assertEquals("1000.00", balance(user));
    assertEquals("CASH_TO_BANK", txns.findById(UUID.fromString(reversal)).orElseThrow().getMovement());
    assertEquals(0, cmp("0", totals(user).path("moneyIn").asText()), "not income");

    String again = create(user, "CREDIT", "8000.00", dining, acct);
    bankReversal(again);
    assertEquals("1000.00", balance(user), "one reversal per withdrawal");
    bankReversal(reversal);
    assertEquals("1000.00", balance(user), "applying it twice changes nothing");
  }

  @Test
  @DisplayName("a reversal after the cash was spent leaves a negative balance and makes the check due now")
  void reversalAfterSpending() throws Exception {
    UUID user = UUID.randomUUID();
    UUID acct = account(user);
    JsonNode opened = open(user, "100.00");
    create(user, "DEBIT", "500.00", cashWithdrawal, acct);
    data(reconcile(user, "conf-" + UUID.randomUUID(), wallet(user).path("version").asText(), "600.00", null));
    spend(user, "s-" + UUID.randomUUID(), "550.00", dining, "\"paymentSource\":\"CASH_WALLET\"");
    assertEquals("50.00", balance(user));
    bankReversal(create(user, "CREDIT", "500.00", dining, acct));
    JsonNode w = wallet(user);
    assertEquals("-450.00", w.path("balance").asText());
    assertFalse(w.path("reviewDueAt").isNull(), "the app asks the customer to count their cash");
    assertTrue(java.time.Instant.parse(w.path("reviewDueAt").asText()).isBefore(java.time.Instant.now().plusSeconds(5)));
    assertFalse(opened.path("version").asText().equals(w.path("version").asText()));
  }
}
