package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiBudgetPolicy;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBudgetPolicyRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.impl.BudgetPolicyProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * "What changed?" (Premium Insights, V1.28.0): access (switch × entitlement), the spending rules,
 * ranking, both periods' drilldowns adding up to the comparison, and account isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("What changed? (Premium Insights)")
class WhatChangedIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiAccountRepository accounts;
  @Autowired private BiBudgetPolicyRepository policies;
  @Autowired private BudgetPolicyProvider policy;
  @Autowired private InsightsProperties properties;

  private static final String API = "/api/insights";
  private final ZoneId zone = ZoneId.of("Asia/Colombo");
  /** A completed month and the one before it: dates that do not move with the test clock. */
  private final YearMonth month = YearMonth.now(zone).minusMonths(1);
  private final YearMonth before = month.minusMonths(1);
  private UUID food;
  private UUID fuel;
  private UUID rent;
  private UUID fun;
  private UUID health;
  private UUID cashWithdrawal;

  @BeforeEach
  void seed() {
    food = ensure("DINING_OUT", "Dining out", 1);
    fuel = ensure("FUEL", "Fuel", 2);
    rent = ensure("HOUSING_WC", "Housing", 3);
    fun = ensure("ENTERTAINMENT", "Entertainment", 4);
    health = ensure("HEALTH_WC", "Health", 5);
    cashWithdrawal = ensure(BiCategory.CASH_WITHDRAWAL_CODE, "Cash withdrawal", 6);
    ensure(BiCategory.OTHER_CODE, "Other", 7);
  }

  @AfterEach
  void reset() {
    switchTo(false);
    properties.getPremium().getUserIds().clear();
  }

  private UUID ensure(String code, String name, int order) {
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
                            .nameSi(name + " si")
                            .nameTa(name + " ta")
                            .iconKey(code.toLowerCase())
                            .colourHex("#123456")
                            .sortOrder((short) order)
                            .isActive(true)
                            .createdAt(OffsetDateTime.now())
                            .build())
                    .getId());
  }

  /** The global switch, as a publish from the Admin Dashboard leaves it. */
  private void switchTo(boolean on) {
    BiBudgetPolicy row =
        policies.findById(BiBudgetPolicy.ID)
            .orElseGet(
                () ->
                    BiBudgetPolicy.builder()
                        .id(BiBudgetPolicy.ID)
                        .needsPct((short) 50)
                        .wantsPct((short) 30)
                        .savingsPct((short) 20)
                        .trailingMonths((short) 3)
                        .defaultAlertPct((short) 80)
                        .paceTolerancePct((short) 0)
                        .alertCategoryApproaching(true)
                        .alertCategoryExceeded(true)
                        .alertBudgetExceeded(true)
                        .build());
    row.setWhatChangedOn(on);
    row.setUpdatedAt(OffsetDateTime.now());
    policies.saveAndFlush(row);
    policy.evict();
  }

  private UUID premium() {
    UUID user = UUID.randomUUID();
    properties.getPremium().getUserIds().add(user.toString());
    return user;
  }

  /** A Premium customer whose records reach back before both periods (a salary credit, not spending). */
  private UUID premiumWithHistory() {
    UUID user = premium();
    save(user, food, "1.00", before.minusMonths(1).atDay(1), null, t -> t.setDirection(TxnDirection.CREDIT));
    return user;
  }

  private ResponseEntity<String> get(UUID user, String path) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(TestTokens.bearer(user));
    return rest.exchange(API + path, HttpMethod.GET, new HttpEntity<>(null, h), String.class);
  }

  private JsonNode ok(UUID user, String path) throws Exception {
    ResponseEntity<String> r = get(user, path);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return json.readTree(r.getBody()).path("data");
  }

  private String error(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("error").asText();
  }

  private BiTransaction spend(UUID user, UUID category, String amount, LocalDate date) {
    return save(user, category, amount, date, null, txn -> {});
  }

  private BiTransaction save(
      UUID user, UUID category, String amount, LocalDate date, UUID account, java.util.function.Consumer<BiTransaction> tweak) {
    BiTransaction t =
        BiTransaction.builder()
            .userId(user)
            .accountId(account)
            .amount(new BigDecimal(amount))
            .currency("LKR")
            .direction(TxnDirection.DEBIT)
            .merchant("Test")
            .txnDate(date)
            .txnTimestamp(OffsetDateTime.now())
            .categoryId(category)
            .autoCategoryId(category)
            .isUserCategorised(false)
            .categorySource(CategorySource.RULE)
            .source(TxnSource.MANUAL)
            .idempotencyKey("wc-" + UUID.randomUUID())
            .build();
    tweak.accept(t);
    return transactions.save(t);
  }

  private UUID account(UUID user, String tail) {
    return accounts
        .save(
            BiAccount.builder()
                .id(UUID.randomUUID())
                .userId(user)
                .bankCode("7056")
                .instrumentType(InstrumentType.BANK)
                .maskedTail(tail)
                .isActive(true)
                .createdAt(OffsetDateTime.now())
                .build())
        .getId();
  }

  private JsonNode change(JsonNode body, UUID category) {
    for (JsonNode c : body.path("changes")) {
      if (category.toString().equals(c.path("categoryId").asText())) {
        return c;
      }
    }
    return null;
  }

  // ---- access ------------------------------------------------------------------------------------

  @Test
  @DisplayName("switch OFF (default): refused for everyone, Premium or not; availability says FEATURE_DISABLED")
  void switchOff() throws Exception {
    UUID user = premium();
    ResponseEntity<String> r = get(user, "/what-changed");
    assertEquals(403, r.getStatusCode().value());
    assertEquals("MR.WCH.E.001", error(r));
    JsonNode a = ok(user, "/what-changed/availability");
    assertEquals("insights.what-changed", a.path("featureKey").asText());
    assertFalse(a.path("enabled").asBoolean());
    assertTrue(a.path("entitled").asBoolean());
    assertFalse(a.path("available").asBoolean());
    assertEquals("FEATURE_DISABLED", a.path("reason").asText());
  }

  @Test
  @DisplayName("switch ON never grants Premium: a customer without the entitlement gets PREMIUM_REQUIRED (an app flag is ignored)")
  void premiumRequired() throws Exception {
    switchTo(true);
    UUID free = UUID.randomUUID();
    ResponseEntity<String> r = get(free, "/what-changed?isPremium=true");
    assertEquals(403, r.getStatusCode().value());
    assertEquals("MR.WCH.E.002", error(r));
    JsonNode a = ok(free, "/what-changed/availability");
    assertTrue(a.path("enabled").asBoolean());
    assertFalse(a.path("entitled").asBoolean());
    assertEquals("PREMIUM_REQUIRED", a.path("reason").asText());
    // Ordinary history stays open.
    assertEquals(200, get(free, "/transactions?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth()).getStatusCode().value());
    assertEquals(200, get(free, "/breakdown/" + food + "/transactions?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth() + "&currency=LKR").getStatusCode().value());
  }

  @Test
  @DisplayName("entitlement expiry: valid through its last day, refused after; a change shows on the next request")
  void entitlementExpiry() throws Exception {
    switchTo(true);
    LocalDate today = LocalDate.now(zone);
    UUID valid = UUID.randomUUID();
    UUID expired = UUID.randomUUID();
    properties.getPremium().getUserIds().add(valid + "@" + today);
    properties.getPremium().getUserIds().add(expired + "@" + today.minusDays(1));
    assertTrue(ok(valid, "/what-changed/availability").path("available").asBoolean());
    assertEquals("MR.WCH.E.002", error(get(expired, "/what-changed")));
    properties.getPremium().getUserIds().clear();
    assertEquals("MR.WCH.E.002", error(get(valid, "/what-changed")), "entitlement removed: refused at once");
  }

  // ---- the comparison ------------------------------------------------------------------------------

  @Test
  @DisplayName("ranked by absolute difference; new, decrease, zero-both omitted; exclusions; refunds not netted; averages and rounding")
  void rulesAndRanking() throws Exception {
    switchTo(true);
    UUID user = premiumWithHistory();
    LocalDate prev = before.atDay(10);
    LocalDate cur = month.atDay(10);
    // Dining out: 1000 → 1333.33 (+333.33, +33.3%), 1 → 2 payments.
    spend(user, food, "1000.00", prev);
    spend(user, food, "1000.00", cur);
    spend(user, food, "333.33", cur.plusDays(1));
    // Housing: 50000 → 45000 (−5000): the largest change, a decrease.
    spend(user, rent, "50000.00", prev);
    spend(user, rent, "45000.00", cur);
    // Entertainment: nothing → 2500: NEW_THIS_PERIOD, no percentage.
    spend(user, fun, "2500.00", cur);
    // Fuel: 4000 → 4000: no change, omitted.
    spend(user, fuel, "4000.00", prev);
    spend(user, fuel, "4000.00", cur);
    // Health: 300 → 0 (−300): a real change, but fourth: not in the top three.
    spend(user, health, "300.00", prev);
    // Excluded everywhere: a cash withdrawal, own-account and wallet movements, a deleted row,
    // another currency, and a refund (money in) that is not netted against spending.
    spend(user, cashWithdrawal, "20000.00", cur);
    save(user, food, "9999.00", cur, null, t -> t.setMovement("OWN_ACCOUNT"));
    save(user, food, "8888.00", cur, null, t -> t.setMovement("BALANCE_CORRECTION"));
    save(user, food, "7777.00", cur, null, t -> t.setDeletedAt(OffsetDateTime.now()));
    save(user, food, "50.00", cur, null, t -> t.setCurrency("USD"));
    save(user, food, "500.00", cur, null, t -> t.setDirection(TxnDirection.CREDIT));
    // A cash expense paid from the wallet counts once.
    save(user, fun, "500.00", cur, null, t -> t.setPaymentSource("CASH_WALLET"));

    JsonNode body = ok(user, "/what-changed?month=" + month);
    assertEquals("AVAILABLE", body.path("status").asText());
    assertEquals("COMPLETED_MONTH", body.path("mode").asText());
    assertEquals(month.atDay(1).toString(), body.path("currentPeriod").path("startDate").asText());
    assertEquals(month.atEndOfMonth().toString(), body.path("currentPeriod").path("endDate").asText());
    assertEquals(before.atEndOfMonth().toString(), body.path("previousPeriod").path("endDate").asText());
    assertEquals("LKR", body.path("currency").asText());
    assertEquals(0, body.path("accountIds").size());
    assertEquals(1, body.path("coverage").path("otherCurrencyExcludedCount").asInt());
    assertTrue(body.path("coverage").path("recordedTransactionsOnly").asBoolean());

    JsonNode changes = body.path("changes");
    assertEquals(3, changes.size());
    assertEquals(rent.toString(), changes.get(0).path("categoryId").asText(), "largest absolute change first");
    assertEquals(fun.toString(), changes.get(1).path("categoryId").asText());
    assertEquals(food.toString(), changes.get(2).path("categoryId").asText());
    assertTrue(change(body, health) == null, "fourth largest not returned");
    assertTrue(change(body, fuel) == null, "unchanged omitted");

    JsonNode housing = changes.get(0);
    assertEquals("CHANGED", housing.path("state").asText());
    assertEquals("DECREASE", housing.path("direction").asText());
    assertEquals("-5000.00", housing.path("difference").asText());
    assertEquals(-10.0, housing.path("percentChange").asDouble(), 0.0001);

    JsonNode ent = changes.get(1);
    assertEquals("NEW_THIS_PERIOD", ent.path("state").asText());
    assertTrue(ent.path("percentChange").isNull());
    assertEquals("3000.00", ent.path("current").path("total").asText(), "cash expense included once");
    assertEquals(2, ent.path("current").path("paymentCount").asInt());
    assertEquals("1500.00", ent.path("current").path("averagePayment").asText());
    assertEquals("0.00", ent.path("previous").path("total").asText());
    assertTrue(ent.path("previous").path("averagePayment").isNull());

    JsonNode dining = changes.get(2);
    assertEquals("1333.33", dining.path("current").path("total").asText(), "exclusions and refund not counted");
    assertEquals(2, dining.path("current").path("paymentCount").asInt());
    assertEquals("666.67", dining.path("current").path("averagePayment").asText(), "rounded half up");
    assertEquals("333.33", dining.path("difference").asText());
    assertEquals(33.3, dining.path("percentChange").asDouble(), 0.0001);
    assertEquals(1, dining.path("paymentCountDifference").asInt());
    assertEquals("Dining out", dining.path("name").asText());
    assertEquals("Dining out si", dining.path("nameSi").asText());
    assertEquals("#123456", dining.path("colourHex").asText());
    JsonNode drill = dining.path("previousTransactions");
    assertEquals("/api/insights/breakdown/" + food + "/transactions", drill.path("path").asText());
    assertEquals(before.atDay(1).toString(), drill.path("from").asText());
    assertEquals(before.atEndOfMonth().toString(), drill.path("to").asText());
    assertEquals("LKR", drill.path("currency").asText());
  }

  @Test
  @DisplayName("every matching row counts, not a page; both periods' drilldowns add up exactly to the comparison")
  void fullHistoryAndDrilldown() throws Exception {
    switchTo(true);
    UUID user = premiumWithHistory();
    for (int i = 0; i < 130; i++) {
      spend(user, food, "10.01", month.atDay(1 + i % 28));
    }
    spend(user, food, "100.00", before.atDay(3));
    save(user, food, "999.00", month.atDay(2), null, t -> t.setMovement("OWN_ACCOUNT"));
    JsonNode body = ok(user, "/what-changed?month=" + month);
    JsonNode c = change(body, food);
    assertEquals("1301.30", c.path("current").path("total").asText());
    assertEquals(130, c.path("current").path("paymentCount").asInt());

    JsonNode d = c.path("currentTransactions");
    BigDecimal sum = BigDecimal.ZERO;
    long rows = 0;
    for (int page = 0; page < 5; page++) {
      JsonNode p =
          ok(user, "/breakdown/" + food + "/transactions?from=" + d.path("from").asText() + "&to=" + d.path("to").asText()
              + "&currency=" + d.path("currency").asText() + "&page=" + page + "&size=100");
      assertEquals("1301.30", p.path("total").asText(), "the drilldown total is the comparison's");
      assertEquals(130, p.path("page").path("totalElements").asInt());
      for (JsonNode t : p.path("transactions")) {
        sum = sum.add(new BigDecimal(t.path("amount").asText()));
        rows++;
      }
      if (p.path("transactions").size() < 100) {
        break;
      }
    }
    assertEquals(130, rows);
    assertEquals(0, new BigDecimal("1301.30").compareTo(sum));
    JsonNode prev = c.path("previousTransactions");
    JsonNode pp =
        ok(user, "/breakdown/" + food + "/transactions?from=" + prev.path("from").asText() + "&to=" + prev.path("to").asText() + "&currency=LKR");
    assertEquals(c.path("previous").path("total").asText(), pp.path("total").asText());
    assertEquals(c.path("previous").path("paymentCount").asInt(), pp.path("page").path("totalElements").asInt());
  }

  @Test
  @DisplayName("accounts: only the chosen accounts on both sides; someone else's account is refused (404) here and in the drilldown")
  void accounts() throws Exception {
    switchTo(true);
    UUID user = premiumWithHistory();
    UUID a = account(user, "1111");
    UUID b = account(user, "2222");
    // Each account's own records reach back before both periods (coverage is per chosen accounts).
    save(user, food, "1.00", before.minusMonths(1).atDay(1), a, t -> t.setDirection(TxnDirection.CREDIT));
    save(user, food, "1.00", before.minusMonths(1).atDay(1), b, t -> t.setDirection(TxnDirection.CREDIT));
    save(user, food, "100.00", before.atDay(5), a, t -> {});
    save(user, food, "400.00", month.atDay(5), a, t -> {});
    save(user, food, "9000.00", month.atDay(5), b, t -> {});
    spend(user, food, "700.00", month.atDay(6)); // cash: no account
    JsonNode onA = ok(user, "/what-changed?month=" + month + "&accountId=" + a);
    assertEquals(a.toString(), onA.path("accountIds").get(0).asText());
    JsonNode c = change(onA, food);
    assertEquals("400.00", c.path("current").path("total").asText());
    assertEquals(a.toString(), c.path("currentTransactions").path("accountIds").get(0).asText());
    JsonNode list =
        ok(user, "/breakdown/" + food + "/transactions?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth() + "&accountId=" + a);
    assertEquals("400.00", list.path("total").asText());
    assertEquals(1, list.path("page").path("totalElements").asInt());

    JsonNode both = ok(user, "/what-changed?month=" + month + "&accountId=" + a + "&accountId=" + b);
    assertEquals("9400.00", change(both, food).path("current").path("total").asText());
    assertEquals("10100.00", change(ok(user, "/what-changed?month=" + month), food).path("current").path("total").asText(), "all accounts and cash");

    UUID stranger = UUID.randomUUID();
    UUID theirs = account(stranger, "3333");
    ResponseEntity<String> refused = get(user, "/what-changed?month=" + month + "&accountId=" + theirs);
    assertEquals(404, refused.getStatusCode().value());
    assertEquals(404, get(user, "/breakdown/" + food + "/transactions?from=" + month.atDay(1) + "&to=" + month.atEndOfMonth() + "&accountId=" + theirs).getStatusCode().value());
  }

  @Test
  @DisplayName("history: none, or starting after the previous period → INSUFFICIENT_HISTORY; history with no change → NO_CHANGES")
  void history() throws Exception {
    switchTo(true);
    UUID none = premium();
    assertEquals("INSUFFICIENT_HISTORY", ok(none, "/what-changed?month=" + month).path("status").asText());

    UUID newcomer = premium();
    spend(newcomer, food, "500.00", month.atDay(3));
    JsonNode partial = ok(newcomer, "/what-changed?month=" + month);
    assertEquals("INSUFFICIENT_HISTORY", partial.path("status").asText());
    assertFalse(partial.path("coverage").path("previousPeriodCovered").asBoolean());
    assertEquals(month.atDay(3).toString(), partial.path("coverage").path("earliestRecordOn").asText());
    assertEquals(0, partial.path("changes").size(), "missing history is not 'spent nothing before'");

    UUID steady = premium();
    spend(steady, food, "500.00", before.atDay(1));
    spend(steady, food, "500.00", month.atDay(1));
    JsonNode same = ok(steady, "/what-changed?month=" + month);
    assertEquals("NO_CHANGES", same.path("status").asText());
    assertTrue(same.path("coverage").path("previousPeriodCovered").asBoolean());
  }

  @Test
  @DisplayName("a future month or a malformed one is refused (400)")
  void periodValidation() throws Exception {
    switchTo(true);
    UUID user = premium();
    ResponseEntity<String> future = get(user, "/what-changed?month=" + YearMonth.now(zone).plusMonths(1));
    assertEquals(400, future.getStatusCode().value());
    assertEquals("MR.WCH.E.003", error(future));
    assertEquals(400, get(user, "/what-changed?month=2026-13").getStatusCode().value());
  }

  @Test
  @DisplayName("nothing is stored: an edit or a recategorisation shows on the next request")
  void liveAfterEdits() throws Exception {
    switchTo(true);
    UUID user = premiumWithHistory();
    spend(user, food, "100.00", before.atDay(4));
    BiTransaction t = spend(user, food, "300.00", month.atDay(4));
    assertEquals("300.00", change(ok(user, "/what-changed?month=" + month), food).path("current").path("total").asText());
    t.setAmount(new BigDecimal("350.00"));
    transactions.save(t);
    assertEquals("350.00", change(ok(user, "/what-changed?month=" + month), food).path("current").path("total").asText());
    t.setCategoryId(fuel);
    transactions.save(t);
    JsonNode after = ok(user, "/what-changed?month=" + month);
    assertEquals("NEW_THIS_PERIOD", change(after, fuel).path("state").asText());
    assertEquals("0.00", change(after, food).path("current").path("total").asText());
    switchTo(false);
    assertEquals("MR.WCH.E.001", error(get(user, "/what-changed?month=" + month)), "switch off: refused at once");
  }

  @Test
  @DisplayName("this month on its first day: NO_COMPLETED_DAYS; otherwise month to date through yesterday")
  void currentMonth() throws Exception {
    switchTo(true);
    UUID user = premium();
    spend(user, food, "100.00", before.atDay(1));
    JsonNode body = ok(user, "/what-changed");
    LocalDate today = LocalDate.now(zone);
    assertEquals("MONTH_TO_DATE", body.path("mode").asText());
    if (today.getDayOfMonth() == 1) {
      assertEquals("NO_COMPLETED_DAYS", body.path("status").asText());
    } else {
      assertFalse(LocalDate.parse(body.path("currentPeriod").path("endDate").asText()).isAfter(today.minusDays(1)), "never today");
      assertEquals(body.path("currentPeriod").path("days").asInt(), body.path("previousPeriod").path("days").asInt());
    }
  }
}
