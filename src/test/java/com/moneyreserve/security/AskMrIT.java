package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAskMrWorth;
import com.moneyreserve.domain.BiBudgetPolicy;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.repository.BiAskMrWorthRepository;
import com.moneyreserve.repository.BiBudgetPolicyRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.impl.AskMrServiceImpl;
import com.moneyreserve.service.impl.BudgetPolicyProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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
 * Ask MR (Premium Insights, V1.29.0): access (switch, Premium, free teaser), the facts for each
 * question, thresholds, one-offs, progress, missing-data notes, "Worth knowing" limits and account
 * isolation. Month answers depend on today's date: before the early-month day they are TOO_EARLY,
 * so those tests are skipped then and the TOO_EARLY test runs instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Ask MR (Premium Insights)")
class AskMrIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiBudgetPolicyRepository policies;
  @Autowired private BiAskMrWorthRepository worth;
  @Autowired private com.moneyreserve.repository.BiAskMrTipRepository tips;
  @Autowired private BudgetPolicyProvider policy;
  @Autowired private InsightsProperties properties;

  private static final String API = "/api/insights/ask";
  private final ZoneId zone = ZoneId.of("Asia/Colombo");
  private final LocalDate today = LocalDate.now(zone);
  private final AskMrServiceImpl.Window month = AskMrServiceImpl.monthWindow(today);
  private UUID dining;
  private UUID fuel;

  @BeforeEach
  void seed() {
    dining = ensure("DINING_OUT", "Dining out", 1);
    fuel = ensure("FUEL", "Fuel", 2);
    ensure(BiCategory.CASH_WITHDRAWAL_CODE, "Cash withdrawal", 6);
    ensure(BiCategory.OTHER_CODE, "Other", 7);
  }

  @AfterEach
  void reset() {
    settings(false, true, 2);
    properties.getPremium().getUserIds().clear();
  }

  // ---- helpers -------------------------------------------------------------------------------------

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

  /** The published settings, as a publish from the Admin Dashboard leaves them. */
  private void settings(boolean on, boolean teaser, int perWeek) {
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
    row.setAskMrOn(on);
    row.setAskMrFreeTeaser(teaser);
    row.setAskMrWorthPerWeek((short) perWeek);
    row.setAskMrMinAmount(1000);
    row.setAskMrMinPct((short) 15);
    row.setAskMrEarlyDay((short) 5);
    row.setAskMrOneOffTenths((short) 20);
    row.setAskMrWeeklySummary(true);
    row.setUpdatedAt(OffsetDateTime.now());
    policies.saveAndFlush(row);
    policy.evict();
  }

  private UUID premium() {
    UUID user = UUID.randomUUID();
    properties.getPremium().getUserIds().add(user.toString());
    return user;
  }

  /** Records reaching back before both periods (a salary credit, not spending). */
  private void history(UUID user) {
    save(user, dining, "1.00", today.minusMonths(4), "Employer", t -> t.setDirection(TxnDirection.CREDIT));
  }

  private BiTransaction spend(UUID user, UUID category, String amount, LocalDate date, String merchant) {
    return save(user, category, amount, date, merchant, t -> {});
  }

  private BiTransaction save(UUID user, UUID category, String amount, LocalDate date, String merchant, Consumer<BiTransaction> tweak) {
    BiTransaction t =
        BiTransaction.builder()
            .userId(user)
            .amount(new BigDecimal(amount))
            .currency("LKR")
            .direction(TxnDirection.DEBIT)
            .merchant(merchant)
            .txnDate(date)
            .txnTimestamp(OffsetDateTime.now())
            .categoryId(category)
            .autoCategoryId(category)
            .isUserCategorised(false)
            .categorySource(CategorySource.RULE)
            .source(TxnSource.MANUAL)
            .idempotencyKey("ask-" + UUID.randomUUID())
            .build();
    tweak.accept(t);
    return transactions.save(t);
  }

  /** {@code n} payments spread over [from, to]. */
  private void many(UUID user, UUID category, int n, String amount, LocalDate from, LocalDate to, String merchant) {
    long days = ChronoUnit.DAYS.between(from, to) + 1;
    for (int i = 0; i < n; i++) {
      spend(user, category, amount, from.plusDays(i % days), merchant);
    }
  }

  private ResponseEntity<String> call(UUID user, HttpMethod method, String path, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(TestTokens.bearer(user));
    return rest.exchange(API + path, method, new HttpEntity<>(body, h), String.class);
  }

  private JsonNode ok(UUID user, String path) throws Exception {
    ResponseEntity<String> r = call(user, HttpMethod.GET, path, null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    return json.readTree(r.getBody()).path("data");
  }

  private String error(ResponseEntity<String> r) throws Exception {
    return json.readTree(r.getBody()).path("error").asText();
  }

  private void monthAnswersAvailable() {
    assumeTrue(month != null && today.getDayOfMonth() >= 5, "month comparisons are TOO_EARLY before the 5th");
  }

  /** Dining up 7,000 (5 → 12 PickMe Food orders), Fuel down 3,000; total 11,000 → 15,000. */
  private UUID diningUp() {
    UUID user = premium();
    history(user);
    many(user, dining, 5, "1000.00", month.prevFrom(), month.prevTo(), "PickMe Food");
    many(user, fuel, 6, "1000.00", month.prevFrom(), month.prevTo(), "Shell");
    many(user, dining, 12, "1000.00", month.from(), month.to(), "PickMe Food");
    many(user, fuel, 3, "1000.00", month.from(), month.to(), "Shell");
    return user;
  }

  private static JsonNode kind(JsonNode list, String kind) {
    for (JsonNode n : list) {
      if (kind.equals(n.path("kind").asText())) {
        return n;
      }
    }
    return null;
  }

  // ---- access --------------------------------------------------------------------------------------

  @Test
  @DisplayName("OFF by default: every Ask MR endpoint is refused (MR.ASK.E.001), Premium or not")
  void offByDefault() throws Exception {
    UUID user = premium();
    for (String path : new String[] {"/questions", "/answer?question=BUDGET", "/worth-knowing", "/weekly", "/categories"}) {
      ResponseEntity<String> r = call(user, HttpMethod.GET, path, null);
      assertEquals(403, r.getStatusCode().value(), path);
      assertEquals("MR.ASK.E.001", error(r), path);
    }
  }

  @Test
  @DisplayName("without Premium: the teaser keeps the headline and transactions, leaves out the why and the tips; teaser off → MR.ASK.E.002")
  void freeTeaser() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode full = ok(user, "/answer?question=SPENDING_CHANGE");
    assertTrue(full.path("lockedPreview").isMissingNode(), "Premium answers carry no preview");
    properties.getPremium().getUserIds().clear(); // the same data, without Premium
    JsonNode a = ok(user, "/answer?question=SPENDING_CHANGE&isPremium=true");
    assertTrue(a.path("premiumLocked").asBoolean());
    // What is waiting, as counts only: the same as the Premium answer holds.
    JsonNode preview = a.path("lockedPreview");
    assertEquals(full.path("drivers").size() + full.path("progress").size(), preview.path("reasons").asInt(), a.toString());
    assertEquals(full.path("tips").size(), preview.path("suggestions").asInt());
    assertTrue(preview.path("reasons").asInt() > 0 && preview.path("suggestions").asInt() > 0, a.toString());
    assertEquals(2, preview.size(), "counts only, never the content");
    assertEquals("INCREASE", a.path("headline").path("kind").asText());
    assertTrue(a.path("drivers").isMissingNode(), a.toString());
    assertTrue(a.path("tips").isMissingNode());
    assertTrue(a.path("progress").isMissingNode());
    for (JsonNode e : a.path("evidence")) {
      assertTrue(e.path("kind").asText().equals("TRANSACTIONS") || e.path("kind").asText().equals("BUDGET"));
    }
    assertFalse(ok(user, "/questions").path("entitled").asBoolean());

    settings(true, false, 2);
    ResponseEntity<String> r = call(user, HttpMethod.GET, "/answer?question=SPENDING_CHANGE", null);
    assertEquals(403, r.getStatusCode().value());
    assertEquals("MR.ASK.E.002", error(r));
  }

  @Test
  @DisplayName("bad input: unknown question, a category question without a category, a bad week → MR.ASK.E.003; another customer's account → 404")
  void badInput() throws Exception {
    settings(true, true, 2);
    UUID user = premium();
    for (String path : new String[] {"/answer?question=WHY_NOT", "/answer?question=CATEGORY_CHANGE", "/answer?question=WEEK&week=last-week"}) {
      ResponseEntity<String> r = call(user, HttpMethod.GET, path, null);
      assertEquals(400, r.getStatusCode().value(), path);
      assertEquals("MR.ASK.E.003", error(r), path);
    }
    assertEquals(404, call(user, HttpMethod.GET, "/answer?question=BUDGET&accountId=" + UUID.randomUUID(), null).getStatusCode().value());
  }

  // ---- spending change -----------------------------------------------------------------------------

  @Test
  @DisplayName("SPENDING_CHANGE: totals, the category behind the increase, progress first, a limit tip, evidence for the same dates")
  void spendingChange() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode a = ok(user, "/answer?question=SPENDING_CHANGE");
    assertEquals("ANSWERED", a.path("status").asText());
    assertFalse(a.path("premiumLocked").asBoolean());
    assertEquals(month.from().toString(), a.path("period").path("from").asText());
    assertEquals(month.prevTo().toString(), a.path("comparePeriod").path("to").asText());
    JsonNode h = a.path("headline");
    assertEquals("INCREASE", h.path("kind").asText());
    assertEquals("15000.00", h.path("amount").asText());
    assertEquals("11000.00", h.path("previous").asText());
    assertEquals("4000.00", h.path("delta").asText());
    assertEquals(36.4, h.path("percent").asDouble(), 0.01);

    JsonNode up = a.path("drivers").get(0);
    assertEquals("CATEGORY_UP", up.path("kind").asText());
    assertEquals("DINING_OUT", up.path("category").path("code").asText());
    assertEquals("7000.00", up.path("delta").asText());
    assertEquals(12, up.path("count").asInt());
    assertEquals(5, up.path("previousCount").asInt());

    JsonNode good = a.path("progress").get(0);
    assertEquals("CATEGORY_DOWN", good.path("kind").asText());
    assertEquals("FUEL", good.path("category").path("code").asText());
    assertEquals("-3000.00", good.path("delta").asText());

    JsonNode tip = a.path("tips").get(0);
    assertEquals("set-limit", tip.path("id").asText());
    assertEquals("DINING_OUT", tip.path("params").path("category").asText());
    assertFalse(tip.path("si").asText().isBlank());
    assertFalse(tip.path("ta").asText().isBlank());

    JsonNode ev = kind(a.path("evidence"), "TRANSACTIONS");
    assertEquals("/api/insights/breakdown/" + dining + "/transactions", ev.path("path").asText());
    assertEquals(month.from().toString(), ev.path("from").asText());
    assertEquals(month.to().toString(), ev.path("to").asText());
    assertEquals("CATEGORY_CHANGE", a.path("followUps").get(0).path("question").asText());
  }

  @Test
  @DisplayName("CATEGORY_CHANGE: more purchases at one merchant, a 'one fewer' tip from its average, transactions and budget evidence")
  void categoryChange() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode a = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + dining);
    assertEquals("INCREASE", a.path("headline").path("kind").asText());
    assertEquals("DINING_OUT", a.path("headline").path("category").path("code").asText());
    JsonNode more = kind(a.path("drivers"), "MORE_PURCHASES");
    assertEquals("PickMe Food", more.path("merchant").asText());
    assertEquals(12, more.path("count").asInt());
    assertEquals(5, more.path("previousCount").asInt());
    assertEquals("1000.00", more.path("averagePayment").asText());
    JsonNode fewer = a.path("tips").get(0);
    assertEquals("one-fewer", fewer.path("id").asText());
    assertEquals("4000.00", fewer.path("params").path("amount").asText());
    assertEquals("PickMe Food", fewer.path("params").path("merchant").asText());
    assertTrue(kind(a.path("evidence"), "BUDGET") != null);

    JsonNode down = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + fuel);
    assertEquals("DECREASE", down.path("headline").path("kind").asText());
    assertEquals("CATEGORY_DOWN", down.path("progress").get(0).path("kind").asText());
    assertEquals(0, down.path("tips").size(), "no suggestions for a decrease");
  }

  @Test
  @DisplayName("a one-off (≥ 2× the usual payment) explains the increase: ONE_OFF with the payment, and no suggestion")
  void oneOff() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = premium();
    history(user);
    for (int d : new int[] {30, 40, 50}) {
      spend(user, fuel, "1500.00", month.from().minusDays(d), "Shell");
    }
    many(user, dining, 10, "500.00", month.prevFrom(), month.prevTo(), "Cafe");
    BiTransaction repair = spend(user, fuel, "18000.00", month.from(), "City Motors");
    many(user, dining, 3, "500.00", month.from(), month.to(), "Cafe");
    JsonNode a = ok(user, "/answer?question=SPENDING_CHANGE");
    JsonNode one = kind(a.path("drivers"), "ONE_OFF");
    assertTrue(one != null, a.toString());
    assertEquals("City Motors", one.path("merchant").asText());
    assertEquals("18000.00", one.path("amount").asText());
    assertEquals("LARGE_PAYMENT", one.path("reason").asText());
    assertEquals(repair.getId().toString(), one.path("transactionId").asText());
    assertEquals("FUEL", one.path("category").path("code").asText());
    assertEquals(0, a.path("tips").size(), "a one-off is not a habit: no suggestion");
    assertEquals(repair.getId().toString(), kind(a.path("evidence"), "TRANSACTION").path("transactionId").asText());
    assertTrue(a.path("forecast").isMissingNode(), "a one-off won't repeat: no month-end estimate");

    // The category's own answer names the one-off once; its patterns leave that payment out.
    JsonNode cat = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + fuel);
    assertEquals("ONE_OFF", cat.path("drivers").get(0).path("kind").asText());
    assertTrue(kind(cat.path("drivers"), "NEW_MERCHANT") == null, cat.path("drivers").toString());
    assertTrue(kind(cat.path("drivers"), "BIGGER_PURCHASES") == null, cat.path("drivers").toString());
    assertEquals(0, cat.path("tips").size());
    assertTrue(cat.path("forecast").isMissingNode());
  }

  @Test
  @DisplayName("not a one-off: a merchant paid twice this period, or a large payment that explains little of the change")
  void notOneOff() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = premium();
    history(user);
    for (int d : new int[] {30, 40, 50}) {
      spend(user, dining, "1000.00", month.from().minusDays(d), "PickMe Food");
    }
    many(user, dining, 5, "1000.00", month.prevFrom(), month.prevTo(), "PickMe Food");
    many(user, fuel, 6, "1000.00", month.prevFrom(), month.prevTo(), "Shell");
    many(user, dining, 9, "1000.00", month.from(), month.to(), "PickMe Food");
    spend(user, dining, "2200.00", month.from(), "Cafe Kumbuk");
    spend(user, dining, "2200.00", month.to(), "Cafe Kumbuk");
    many(user, fuel, 6, "1000.00", month.from(), month.to(), "Shell");
    JsonNode a = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + dining);
    assertTrue(kind(a.path("drivers"), "ONE_OFF") == null, a.path("drivers").toString());
    assertEquals("MORE_PURCHASES", a.path("drivers").get(0).path("kind").asText());
    assertTrue(a.path("tips").size() > 0, "a pattern can get a suggestion");
  }

  @Test
  @DisplayName("one category, Premium: side by side with the payments, month-end estimate, 3-month trend, top places")
  void categoryExtras() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode a = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + dining);

    JsonNode cur = a.path("sideBySide").path("current");
    JsonNode prev = a.path("sideBySide").path("previous");
    assertEquals(month.from().toString(), cur.path("from").asText());
    assertEquals(month.prevTo().toString(), prev.path("to").asText());
    assertEquals(12, cur.path("count").asInt());
    assertEquals("12000.00", cur.path("amount").asText());
    assertEquals(5, cur.path("payments").size(), "at most five payments; the count has them all");
    assertEquals("PickMe Food", cur.path("payments").get(0).path("merchant").asText());
    assertEquals(5, prev.path("count").asInt());
    assertEquals("5000.00", prev.path("amount").asText());
    assertEquals(5, prev.path("payments").size());
    String newest = cur.path("payments").get(0).path("date").asText();
    for (JsonNode p : cur.path("payments")) {
      assertTrue(p.path("date").asText().compareTo(newest) <= 0, "newest first");
    }

    // At this pace: 12,000 over the completed days, scaled to the month, rounded to 100.
    java.time.YearMonth ym = java.time.YearMonth.from(month.from());
    BigDecimal pace =
        new BigDecimal("12000").multiply(BigDecimal.valueOf(ym.lengthOfMonth())).divide(BigDecimal.valueOf(month.to().getDayOfMonth()), 0, java.math.RoundingMode.HALF_UP);
    BigDecimal rounded = pace.divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    assertEquals(rounded.setScale(2).toPlainString(), a.path("forecast").path("amount").asText(), a.path("forecast").toString());
    assertEquals(ym.toString(), a.path("forecast").path("month").asText());
    assertEquals("5000.00", a.path("forecast").path("previousMonth").asText());

    // Three full months and this one; records reach back four months, so none is left out.
    JsonNode trend = a.path("trend");
    assertEquals(4, trend.size(), trend.toString());
    assertEquals(ym.minusMonths(3).toString(), trend.get(0).path("month").asText());
    assertEquals("5000.00", trend.get(2).path("amount").asText());
    assertEquals("12000.00", trend.get(3).path("amount").asText());
    assertTrue(trend.get(3).path("partial").asBoolean());
    assertFalse(trend.get(2).path("partial").asBoolean());

    JsonNode place = a.path("topPlaces").get(0);
    assertEquals("PickMe Food", place.path("merchant").asText());
    assertEquals(12, place.path("count").asInt());
    assertEquals("12000.00", place.path("amount").asText());

    // The whole month: counts and totals side by side (no payment list), an estimate and a trend.
    JsonNode all = ok(user, "/answer?question=SPENDING_CHANGE");
    assertEquals(15, all.path("sideBySide").path("current").path("count").asInt());
    assertEquals("11000.00", all.path("sideBySide").path("previous").path("amount").asText());
    assertTrue(all.path("sideBySide").path("current").path("payments").isMissingNode());
    assertTrue(all.path("forecast").path("amount").isTextual());
    assertEquals(4, all.path("trend").size());
    assertTrue(all.path("topPlaces").isMissingNode());

    // Without Premium none of it is sent.
    properties.getPremium().getUserIds().clear();
    JsonNode locked = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + dining);
    for (String part : new String[] {"sideBySide", "forecast", "trend", "topPlaces"}) {
      assertTrue(locked.path(part).isMissingNode(), part + ": " + locked);
    }
  }

  /** A category with an Ask MR type (V1.30.0), created once per test class context. */
  private UUID typed(String code, String name, String type) {
    UUID id = ensure(code, name, 20);
    BiCategory c = categories.findById(id).orElseThrow();
    c.setSpendType(type);
    categories.saveAndFlush(c);
    return id;
  }

  @Test
  @DisplayName("Compare a category: this month's categories by share, with the change and a 'changed' flag; free users too")
  void picker() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode p = ok(user, "/categories");
    assertEquals("ANSWERED", p.path("status").asText());
    assertEquals("15000.00", p.path("total").asText());
    JsonNode first = p.path("items").get(0);
    assertEquals("DINING_OUT", first.path("category").path("code").asText());
    assertEquals("12000.00", first.path("amount").asText());
    assertEquals(80.0, first.path("share").asDouble(), 0.01);
    assertEquals(12, first.path("count").asInt());
    assertEquals("7000.00", first.path("delta").asText());
    assertTrue(first.path("changed").asBoolean());
    JsonNode second = p.path("items").get(1);
    assertEquals("FUEL", second.path("category").path("code").asText());
    assertEquals("-3000.00", second.path("delta").asText());
    assertTrue(second.path("changed").asBoolean(), "down 50%");
    assertEquals(2, p.path("items").size());

    properties.getPremium().getUserIds().clear();
    assertEquals(2, ok(user, "/categories").path("items").size(), "the list is part of the free teaser");
  }

  @Test
  @DisplayName("bills: compared bill by bill, a possible double payment first, only a 'check the bill' tip, no pace; the whole month says 'a bigger bill'")
  void bills() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID utilities = typed("UTILITIES", "Utilities", BiCategory.TYPE_BILL);
    UUID user = premium();
    history(user);
    spend(user, utilities, "4000.00", month.prevFrom(), "CEB");
    spend(user, utilities, "3000.00", month.prevFrom(), "Dialog");
    many(user, dining, 10, "1000.00", month.prevFrom(), month.prevTo(), "Cafe");
    spend(user, utilities, "6000.00", month.from(), "CEB");
    spend(user, utilities, "6000.00", month.from().plusDays(2), "CEB");
    spend(user, utilities, "3500.00", month.from(), "Dialog");
    many(user, dining, 10, "1000.00", month.from(), month.to(), "Cafe");

    JsonNode a = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + utilities);
    assertEquals("INCREASE", a.path("headline").path("kind").asText());
    assertEquals("BILL", a.path("headline").path("category").path("type").asText());
    JsonNode d = a.path("drivers");
    assertEquals("DOUBLE_PAYMENT", d.get(0).path("kind").asText(), d.toString());
    assertEquals("CEB", d.get(0).path("merchant").asText());
    assertEquals("6000.00", d.get(0).path("amount").asText());
    assertEquals("BILL_UP", d.get(1).path("kind").asText());
    assertEquals("CEB", d.get(1).path("merchant").asText());
    assertEquals("12000.00", d.get(1).path("amount").asText());
    assertEquals("4000.00", d.get(1).path("previousAmount").asText());
    assertEquals("Dialog", d.get(2).path("merchant").asText());
    assertTrue(kind(d, "ONE_OFF") == null && kind(d, "MORE_PURCHASES") == null, "a bill is not a habit or a one-off");
    assertEquals(1, a.path("tips").size(), a.path("tips").toString());
    assertEquals("check-bill", a.path("tips").get(0).path("id").asText());
    assertTrue(a.path("forecast").isMissingNode(), "a bill comes once a month: no pace");

    JsonNode all = ok(user, "/answer?question=SPENDING_CHANGE");
    JsonNode bill = kind(all.path("drivers"), "BILL_UP");
    assertTrue(bill != null, all.path("drivers").toString());
    assertEquals("UTILITIES", bill.path("category").path("code").asText());
    assertEquals(0, all.path("tips").size(), "no cut-back tip for a bill");
    // Bills paid so far count once; the rest is paced over the month.
    java.time.YearMonth ym = java.time.YearMonth.from(month.from());
    BigDecimal pace =
        new BigDecimal("10000").multiply(BigDecimal.valueOf(ym.lengthOfMonth())).divide(BigDecimal.valueOf(month.to().getDayOfMonth()), 0, java.math.RoundingMode.HALF_UP).add(new BigDecimal("15500"));
    BigDecimal rounded = pace.divide(BigDecimal.valueOf(100), 0, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    assertEquals(rounded.setScale(2).toPlainString(), all.path("forecast").path("amount").asText());

    JsonNode card = ok(user, "/worth-knowing").path("card");
    if ("UTILITIES".equals(card.path("category").path("code").asText())) {
      assertEquals("BILL", card.path("category").path("type").asText(), "the card carries the type for bill wording");
    }
  }

  @Test
  @DisplayName("essentials: no cut-back tip for health; for other essentials a limit but never 'one fewer'")
  void essentials() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID health = typed("HEALTH_CARE", "Health", BiCategory.TYPE_ESSENTIAL);
    UUID transport = typed("TRANSPORT", "Transport", BiCategory.TYPE_ESSENTIAL);
    UUID user = premium();
    history(user);
    many(user, health, 2, "1000.00", month.prevFrom(), month.prevTo(), "Asiri Hospital");
    many(user, health, 6, "1000.00", month.from(), month.to(), "Asiri Hospital");
    many(user, transport, 5, "1000.00", month.prevFrom(), month.prevTo(), "Uber");
    many(user, transport, 12, "1000.00", month.from(), month.to(), "Uber");

    JsonNode h = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + health);
    assertEquals("INCREASE", h.path("headline").path("kind").asText());
    assertEquals("ESSENTIAL", h.path("headline").path("category").path("type").asText());
    assertEquals("MORE_PURCHASES", h.path("drivers").get(0).path("kind").asText(), "counts, as for any category");
    assertEquals(0, h.path("tips").size(), "never suggest cutting health");

    JsonNode t = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + transport);
    List<String> ids = new java.util.ArrayList<>();
    t.path("tips").forEach(x -> ids.add(x.path("id").asText()));
    assertFalse(ids.contains("one-fewer"), ids.toString());
    assertTrue(ids.contains("set-limit") || ids.contains("review-limit"), ids.toString());
  }

  @Test
  @DisplayName("admin: the Ask MR type is a draft until published; NONE clears it; leaving it out keeps it; anything else is refused")
  void adminType() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    String base = "{\"code\":\"WATER_IT\",\"nameEn\":\"Water\",\"iconKey\":\"ic_other\",\"sortOrder\":70,\"active\":true";
    ResponseEntity<String> created = admin.config(HttpMethod.POST, "/categories", base + ",\"spendType\":\"BILL\"}");
    assertEquals(201, created.getStatusCode().value(), created.getBody());
    String id = AdminTestSupport.data(created).path("item").path("id").asText();
    assertTrue(categories.findByCodeAndIsActiveTrue("WATER_IT").isEmpty(), "a draft until published");
    assertTrue(admin.publishAll().getStatusCode().is2xxSuccessful());
    assertEquals("BILL", categories.findByCodeAndIsActiveTrue("WATER_IT").orElseThrow().getSpendType());

    ResponseEntity<String> edited = admin.config(HttpMethod.PUT, "/categories/" + id, base + ",\"colourHex\":\"#336699\"}");
    assertEquals(200, edited.getStatusCode().value(), edited.getBody());
    ResponseEntity<String> again = admin.publishAll();
    assertTrue(again.getStatusCode().is2xxSuccessful(), again.getBody());
    assertEquals("BILL", categories.findByCodeAndIsActiveTrue("WATER_IT").orElseThrow().getSpendType(), "left out: kept");

    admin.config(HttpMethod.PUT, "/categories/" + id, base + ",\"spendType\":\"NONE\"}");
    assertTrue(admin.publishAll().getStatusCode().is2xxSuccessful());
    assertNull(categories.findByCodeAndIsActiveTrue("WATER_IT").orElseThrow().getSpendType(), "NONE clears it");

    ResponseEntity<String> bad = admin.config(HttpMethod.PUT, "/categories/" + id, base + ",\"spendType\":\"LUXURY\"}");
    assertEquals(400, bad.getStatusCode().value(), bad.getBody());
  }

  @Test
  @DisplayName("unclear merchant names (POS, codes) are never named: counts instead, no one-fewer tip, no top place")
  void unclearNames() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = premium();
    history(user);
    many(user, dining, 5, "1000.00", month.prevFrom(), month.prevTo(), "POS");
    many(user, fuel, 2, "1000.00", month.prevFrom(), month.prevTo(), "Shell");
    many(user, dining, 12, "1000.00", month.from(), month.to(), "POS");
    many(user, fuel, 2, "1000.00", month.from(), month.to(), "Shell");
    JsonNode a = ok(user, "/answer?question=CATEGORY_CHANGE&categoryId=" + dining);
    JsonNode more = kind(a.path("drivers"), "MORE_PURCHASES");
    assertTrue(more != null, a.path("drivers").toString());
    assertTrue(more.path("merchant").isMissingNode(), more.toString());
    assertEquals(12, more.path("count").asInt());
    assertEquals(5, more.path("previousCount").asInt());
    assertTrue(kind(a.path("drivers"), "NEW_MERCHANT") == null);
    for (JsonNode tip : a.path("tips")) {
      assertFalse("one-fewer".equals(tip.path("id").asText()), "no merchant to name");
    }
    assertTrue(a.path("topPlaces").isMissingNode());
    assertTrue(a.path("sideBySide").path("current").path("payments").get(0).path("merchant").isMissingNode());
    assertFalse(a.toString().contains("\"POS\""), a.toString());

    // Where the money went names the biggest clear merchant, not "POS".
    JsonNode where = ok(user, "/answer?question=WHERE_MONEY_WENT");
    assertEquals("Shell", kind(where.path("drivers"), "TOP_MERCHANT").path("merchant").asText());
  }

  @Test
  @DisplayName("thresholds: under LKR 1,000 or under 15% is STEADY, not a change")
  void thresholds() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID small = premium();
    history(small);
    many(small, dining, 10, "1000.00", month.prevFrom(), month.prevTo(), "Cafe");
    many(small, dining, 3, "3500.00", month.from(), month.to(), "Cafe"); // 10,500: +500
    assertEquals("STEADY", ok(small, "/answer?question=SPENDING_CHANGE").path("headline").path("kind").asText());

    UUID tenPct = premium();
    history(tenPct);
    many(tenPct, dining, 10, "10000.00", month.prevFrom(), month.prevTo(), "Cafe");
    many(tenPct, dining, 2, "55000.00", month.from(), month.to(), "Cafe"); // 110,000: +10%
    many(tenPct, fuel, 1, "0.01", month.from(), month.to(), "Shell");
    JsonNode a = ok(tenPct, "/answer?question=SPENDING_CHANGE");
    assertEquals("STEADY", a.path("headline").path("kind").asText());
    assertEquals(0, a.path("drivers").size());
  }

  @Test
  @DisplayName("not enough data: no records before the compared period → NOT_ENOUGH_DATA, and the question is listed as unavailable")
  void notEnoughData() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = premium();
    many(user, dining, 5, "1000.00", month.from(), month.to(), "Cafe");
    assertEquals("NOT_ENOUGH_DATA", ok(user, "/answer?question=SPENDING_CHANGE").path("status").asText());
    JsonNode q = ok(user, "/questions").path("questions");
    assertEquals("SPENDING_CHANGE", q.get(0).path("id").asText());
    assertFalse(q.get(0).path("available").asBoolean());
    assertEquals("NOT_ENOUGH_DATA", q.get(0).path("reason").asText());
    assertTrue(q.get(1).path("available").asBoolean(), "where the money went needs only this month");
  }

  @Test
  @DisplayName("early in the month: month comparisons are TOO_EARLY with an EARLY_MONTH note")
  void tooEarly() throws Exception {
    assumeTrue(month == null || today.getDayOfMonth() < 5, "only before the 5th");
    settings(true, true, 2);
    JsonNode a = ok(premium(), "/answer?question=SPENDING_CHANGE");
    assertEquals("TOO_EARLY", a.path("status").asText());
    assertEquals("EARLY_MONTH", a.path("notes").get(0).path("kind").asText());
  }

  @Test
  @DisplayName("notes: uncategorised spending over 20% and bank SMS stopping are said plainly")
  void notes() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    save(user, dining, "9000.00", month.from(), "Unknown", t -> t.setCategorySource(CategorySource.FALLBACK));
    save(user, dining, "10.00", today.minusDays(20), "Bank", t -> t.setSource(TxnSource.SMS_ANDROID));
    JsonNode a = ok(user, "/answer?question=SPENDING_CHANGE");
    JsonNode unsorted = kind(a.path("notes"), "UNCATEGORISED_HIGH");
    assertEquals("9000.00", unsorted.path("amount").asText());
    assertTrue(kind(a.path("evidence"), "UNCATEGORISED") != null);
    assertTrue(kind(a.path("notes"), "SMS_GAP") != null, a.path("notes").toString());
  }

  // ---- other questions -----------------------------------------------------------------------------

  @Test
  @DisplayName("WHERE_MONEY_WENT: this month's total, top categories with shares, the top merchant")
  void whereMoneyWent() throws Exception {
    settings(true, true, 2);
    UUID user = premium();
    LocalDate first = today.withDayOfMonth(1);
    many(user, dining, 3, "1000.00", first, today, "PickMe Food");
    spend(user, fuel, "4000.00", today, "Shell");
    JsonNode a = ok(user, "/answer?question=WHERE_MONEY_WENT");
    assertEquals("SUMMARY", a.path("headline").path("kind").asText());
    assertEquals("7000.00", a.path("headline").path("amount").asText());
    JsonNode top = a.path("drivers").get(0);
    assertEquals("TOP_CATEGORY", top.path("kind").asText());
    assertEquals("FUEL", top.path("category").path("code").asText());
    assertEquals(57.1, top.path("share").asDouble(), 0.01);
    JsonNode merchant = kind(a.path("drivers"), "TOP_MERCHANT");
    assertEquals("Shell", merchant.path("merchant").asText());
  }

  @Test
  @DisplayName("BUDGET: no target → NO_TARGET; no income recorded lately → INCOME_INCOMPLETE, which goes once income is recorded")
  void budget() throws Exception {
    settings(true, true, 2);
    UUID user = premium();
    spend(user, dining, "2500.00", today, "Cafe");
    JsonNode a = ok(user, "/answer?question=BUDGET");
    assertEquals("NO_TARGET", a.path("headline").path("kind").asText());
    assertEquals("2500.00", a.path("headline").path("amount").asText());
    assertTrue(kind(a.path("notes"), "INCOME_INCOMPLETE") != null);
    assertTrue(kind(a.path("evidence"), "BUDGET") != null);
    save(user, dining, "80000.00", today, "Employer", t -> t.setDirection(TxnDirection.CREDIT));
    assertTrue(kind(ok(user, "/answer?question=BUDGET").path("notes"), "INCOME_INCOMPLETE") == null);
  }

  @Test
  @DisplayName("WEEK and the weekly summary: Monday to today against the same days the week before")
  void week() throws Exception {
    settings(true, true, 2);
    UUID user = premium();
    history(user);
    AskMrServiceImpl.Window w = AskMrServiceImpl.weekWindow(today, null);
    many(user, dining, 4, "1000.00", w.prevFrom(), w.prevTo(), "Cafe");
    many(user, dining, 2, "5000.00", w.from(), w.to(), "Cafe");
    JsonNode a = ok(user, "/weekly");
    assertEquals("WEEK", a.path("question").asText());
    assertEquals("ANSWERED", a.path("status").asText());
    assertEquals(w.from().toString(), a.path("period").path("from").asText());
    assertEquals("WEEK", a.path("headline").path("subject").asText());
    assertEquals("INCREASE", a.path("headline").path("kind").asText());
    assertEquals("6000.00", a.path("headline").path("delta").asText());
    assertEquals(0, a.path("tips").size());
  }

  // ---- "Worth knowing" -----------------------------------------------------------------------------

  @Test
  @DisplayName("Worth knowing: the biggest change, the same card all day, gone once dismissed; dismissing someone else's card is 404")
  void worthKnowing() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 2);
    UUID user = diningUp();
    JsonNode card = ok(user, "/worth-knowing").path("card");
    assertEquals("CATEGORY:DINING_OUT:UP", card.path("topic").asText());
    assertEquals("INCREASE", card.path("kind").asText());
    assertEquals("7000.00", card.path("delta").asText());
    assertEquals("CATEGORY_CHANGE", card.path("question").asText());
    assertEquals(card.path("id").asText(), ok(user, "/worth-knowing").path("card").path("id").asText(), "same card all day");

    assertEquals(404, call(premium(), HttpMethod.POST, "/worth-knowing/" + card.path("id").asText() + "/dismiss", null).getStatusCode().value());
    assertEquals(200, call(user, HttpMethod.POST, "/worth-knowing/" + card.path("id").asText() + "/dismiss", null).getStatusCode().value());
    assertTrue(ok(user, "/worth-knowing").path("card").isMissingNode(), "nothing more today once dismissed");
  }

  @Test
  @DisplayName("Worth knowing: at most N a week, and a topic shown in the last 14 days is skipped")
  void worthKnowingLimits() throws Exception {
    monthAnswersAvailable();
    settings(true, true, 1);
    UUID capped = diningUp();
    worth.save(BiAskMrWorth.builder().id(UUID.randomUUID()).userId(capped).topic("CATEGORY:OTHER:UP").shownOn(today.minusDays(3)).build());
    assertTrue(ok(capped, "/worth-knowing").path("card").isMissingNode(), "one already shown this week");

    settings(true, true, 2);
    UUID repeat = diningUp();
    worth.save(BiAskMrWorth.builder().id(UUID.randomUUID()).userId(repeat).topic("CATEGORY:DINING_OUT:UP").shownOn(today.minusDays(10)).build());
    JsonNode card = ok(repeat, "/worth-knowing").path("card");
    assertNotEquals("CATEGORY:DINING_OUT:UP", card.path("topic").asText());
    assertEquals("CATEGORY:FUEL:DOWN", card.path("topic").asText());
  }

  // ---- admin -------------------------------------------------------------------------------------

  @Test
  @DisplayName("admin: Ask MR settings are a draft until published, then live; invalid thresholds are refused")
  void adminPublish() throws Exception {
    settings(false, true, 2);
    UUID customer = premium();
    assertEquals(403, call(customer, HttpMethod.GET, "/questions", null).getStatusCode().value());

    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    String base =
        "{\"needsPct\":50,\"wantsPct\":30,\"savingsPct\":20,\"trailingMonths\":3,\"defaultAlertPct\":80,"
            + "\"paceTolerancePct\":0,\"alertCategoryApproaching\":true,\"alertCategoryExceeded\":true,"
            + "\"alertBudgetExceeded\":true,\"askMr\":{\"enabled\":true,\"freeTeaser\":true,\"weeklySummary\":true,"
            + "\"worthKnowingPerWeek\":2,\"minChangeAmount\":1000,\"minChangePct\":";
    ResponseEntity<String> bad = admin.config(HttpMethod.PUT, "/budget-policy", base + "0,\"earlyMonthDay\":5,\"oneOffTenths\":20}}");
    assertTrue(bad.getStatusCode().is4xxClientError(), bad.getBody());

    ResponseEntity<String> draft = admin.config(HttpMethod.PUT, "/budget-policy", base + "15,\"earlyMonthDay\":5,\"oneOffTenths\":20}}");
    assertEquals(200, draft.getStatusCode().value(), draft.getBody());
    assertEquals(403, call(customer, HttpMethod.GET, "/questions", null).getStatusCode().value(), "not live before publish");
    assertEquals(201, admin.publishAll().getStatusCode().value());
    assertEquals(200, call(customer, HttpMethod.GET, "/questions", null).getStatusCode().value());
    assertTrue(policies.findById(BiBudgetPolicy.ID).orElseThrow().getAskMrOn());
  }

  /** The four seed tips (Flyway seeds them in real databases; the test database starts empty). */
  private UUID seedTips() {
    UUID setLimit = null;
    String[][] seed = {
      {"set-limit", "If you'd like {category} closer to {previousPeriod}'s level, you could set a monthly limit for it."},
      {"review-limit", "If you'd like, you could review your {category} limit for the rest of the month."},
      {"one-fewer", "One {merchant} purchase fewer a week would keep about {amount} a month."},
      {"daily-pace", "To finish the month within your target, about {amount} a day would do it."}
    };
    for (String[] s : seed) {
      UUID id = UUID.nameUUIDFromBytes(("ask-mr-tip:" + s[0]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      tips.save(
          com.moneyreserve.domain.BiAskMrTip.builder()
              .id(id)
              .code(s[0])
              .textEn(s[1])
              .textSi(s[1] + " (si)")
              .textTa(s[1] + " (ta)")
              .sortOrder((short) 0)
              .updatedAt(OffsetDateTime.now())
              .build());
      if (s[0].equals("set-limit")) {
        setLimit = id;
      }
    }
    return setLimit;
  }

  @Test
  @DisplayName("admin tips: new wording is a draft until published, then in answers; placeholders must stay; blaming words are refused; customers can't edit")
  void adminTips() throws Exception {
    monthAnswersAvailable();
    UUID setLimit = seedTips();
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    ResponseEntity<String> list = admin.config(HttpMethod.GET, "/ask-mr-tips", null);
    assertEquals(200, list.getStatusCode().value(), list.getBody());
    assertTrue(list.getBody().contains("set-limit"));

    String good =
        "{\"textEn\":\"You could set a monthly {category} limit near {previousPeriod}'s level.\","
            + "\"textSi\":\"{category} {previousPeriod} si\",\"textTa\":\"{category} {previousPeriod} ta\",\"active\":true}";
    String lostPlaceholder = good.replace("near {previousPeriod}'s level", "if you like");
    ResponseEntity<String> r1 = admin.config(HttpMethod.PUT, "/ask-mr-tips/" + setLimit, lostPlaceholder);
    assertEquals(400, r1.getStatusCode().value(), r1.getBody());
    String blaming = good.replace("You could set", "You wasted money. Set");
    ResponseEntity<String> r2 = admin.config(HttpMethod.PUT, "/ask-mr-tips/" + setLimit, blaming);
    assertEquals(400, r2.getStatusCode().value(), r2.getBody());
    assertTrue(r2.getBody().contains("wasted"));

    ResponseEntity<String> draft = admin.config(HttpMethod.PUT, "/ask-mr-tips/" + setLimit, good);
    assertEquals(200, draft.getStatusCode().value(), draft.getBody());
    settings(true, true, 2);
    UUID user = diningUp();
    assertTrue(
        ok(user, "/answer?question=SPENDING_CHANGE").path("tips").get(0).path("en").asText().startsWith("If you'd like"),
        "not live before publish");
    assertEquals(201, admin.publishAll().getStatusCode().value());
    assertEquals(
        "You could set a monthly {category} limit near {previousPeriod}'s level.",
        ok(user, "/answer?question=SPENDING_CHANGE").path("tips").get(0).path("en").asText());

    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), "USER"));
    assertEquals(
        403,
        rest.exchange(AdminTestSupport.CONFIG + "/ask-mr-tips/" + setLimit, HttpMethod.PUT, new HttpEntity<>(good, h), String.class)
            .getStatusCode()
            .value());
  }

  @Test
  @DisplayName("admin usage: answers shown and evidence opened per question per day, counts only")
  void adminUsage() throws Exception {
    settings(true, true, 2);
    UUID user = premium();
    ok(user, "/answer?question=BUDGET");
    ok(user, "/answer?question=BUDGET");
    assertEquals(200, call(user, HttpMethod.POST, "/evidence-opened", "{\"question\":\"BUDGET\"}").getStatusCode().value());
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    ResponseEntity<String> r = admin.call(HttpMethod.GET, "/api/insights/admin/ask-mr/usage?days=7", null);
    assertEquals(200, r.getStatusCode().value(), r.getBody());
    JsonNode row = null;
    for (JsonNode n : json.readTree(r.getBody()).path("data")) {
      if ("BUDGET".equals(n.path("question").asText()) && today.toString().equals(n.path("day").asText())) {
        row = n;
      }
    }
    assertTrue(row != null, r.getBody());
    assertTrue(row.path("shown").asInt() >= 2);
    assertTrue(row.path("evidence").asInt() >= 1);
    assertFalse(r.getBody().contains(user.toString()), "no customer in usage");
  }
}
