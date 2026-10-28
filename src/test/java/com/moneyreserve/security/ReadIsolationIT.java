package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiBudgetAlert;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BudgetAlertType;
import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
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
 * Every read endpoint, checked against the only authorisation this service has.
 *
 * <p>{@link OwnershipIsolationIT} proved the writes: one customer cannot edit or delete another's
 * transaction. This proves the reads, which fail more quietly. A finder that forgot its {@code
 * userId} does not throw and does not log; it returns somebody else's money to a customer who
 * asked for their own, and every other test in the suite passes because every other test uses one
 * customer.
 *
 * <p>Each endpoint is checked in <b>both directions</b>, and the second direction is the one that
 * makes the test worth having. Asserting only that Bob cannot see Alice's figure would pass against
 * an endpoint that returns nothing to anybody. So every case first asserts that Alice <i>can</i>
 * see her own marker, which fails loudly if the endpoint stops surfacing the figure and the
 * isolation check quietly becomes vacuous.
 *
 * <p>{@code GET /api/insights/categories} is deliberately absent. It serves the shared category
 * reference list, which is the same for every customer by design; there is nothing there to
 * isolate, and a test pretending otherwise would be noise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Read isolation between customers")
class ReadIsolationIT {


  /** Distinctive enough that a stray match anywhere is a finding, not a coincidence. */
  private static final String SPEND = "918273";

  private static final String TARGET = "1654321";
  private static final String ACCOUNT_LABEL = "ALICE-PRIVATE-ACCOUNT";
  private static final String ALERT_AMOUNT = "445566";

  /**
   * Recommendations needs its own marker, and the reason is worth knowing before reading the seed.
   *
   * <p>That endpoint samples the three <i>complete</i> months before the one being viewed and never
   * the current partial one, deliberately: a recommendation that moved every time the customer
   * bought lunch would not be a recommendation. So {@link #SPEND}, dated today, is invisible to
   * it — correctly. The per-category figure is also a median over those three months, so a single
   * month's spend medians to zero; the marker has to appear in two of the three to survive.
   *
   * <p>This is exactly what the both-directions check is for. With {@code SPEND} as the marker the
   * isolation assertion here would have passed forever against an endpoint that never showed Alice
   * anything.
   */
  private static final String TRAILING = "523491";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiAccountRepository accounts;
  @Autowired private BiBudgetAlertRepository alerts;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private UUID diningId;

  @BeforeEach
  void seedAlicesWorld() {
    diningId = ensureCategory("DINING_OUT", "Dining out");
    ensureCategory(BiCategory.OTHER_CODE, "Other");

    createTransaction(alice, SPEND + ".00", LocalDate.now());

    // Two of the three trailing months, so the median is the marker rather than zero. Dated in
    // complete months, they are invisible to every current-month read above and cannot disturb
    // those markers.
    createTransaction(alice, TRAILING + ".00", LocalDate.now().minusMonths(1).withDayOfMonth(15));
    createTransaction(alice, TRAILING + ".00", LocalDate.now().minusMonths(2).withDayOfMonth(15));

    setBudget(alice, TARGET + ".00");
    saveAccount(alice);
    saveAlert(alice);
  }

  // ---------------------------------------------------------------------------------
  // The sweep
  // ---------------------------------------------------------------------------------

  @TestFactory
  @DisplayName("one customer's figures never appear in another's reads")
  Stream<DynamicTest> readsAreScopedToTheCaller() {
    record Read(String label, String path, String marker) {}

    List<Read> reads =
        List.of(
            new Read("summary", "/api/insights/summary", SPEND),
            new Read("breakdown", "/api/insights/breakdown", SPEND),
            new Read(
                "category transactions",
                "/api/insights/breakdown/" + diningId + "/transactions",
                SPEND),
            new Read("activity", "/api/insights/activity", SPEND),
            new Read("recommendations", "/api/insights/recommendations", TRAILING),
            new Read("budget", "/api/insights/budget", TARGET),
            new Read("accounts", "/api/insights/accounts", ACCOUNT_LABEL),
            new Read("alerts", "/api/insights/alerts", ALERT_AMOUNT));

    return reads.stream()
        .map(
            read ->
                DynamicTest.dynamicTest(
                    read.label(),
                    () -> {
                      String alicesBody = body(get(read.path(), alice), read.label() + " (Alice)");
                      assertTrue(
                          alicesBody.contains(read.marker()),
                          "Alice cannot see her own "
                              + read.marker()
                              + " at "
                              + read.path()
                              + ", so the isolation check below proves nothing. Body: "
                              + alicesBody);

                      String bobsBody = body(get(read.path(), bob), read.label() + " (Bob)");
                      assertFalse(
                          bobsBody.contains(read.marker()),
                          "Alice's "
                              + read.marker()
                              + " appeared in Bob's "
                              + read.path()
                              + ": "
                              + bobsBody);
                    }));
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private ResponseEntity<String> get(String path, UUID user) {
    return rest.exchange(path, HttpMethod.GET, as(user, null), String.class);
  }

  private String body(ResponseEntity<String> response, String what) {
    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        what + " answered " + response.getStatusCode() + ": " + response.getBody());
    return response.getBody() == null ? "" : response.getBody();
  }

  private HttpEntity<String> as(UUID user, String payload) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(user));
    return new HttpEntity<>(payload, headers);
  }

  private UUID ensureCategory(String code, String name) {
    // Flyway is off on the test profile, so the categoriser's reference rows have to be put here.
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

  private void createTransaction(UUID user, String amount, LocalDate txnDate) {
    String payload =
        "{\"amount\":"
            + amount
            + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Isolation Cafe\","
            + "\"txnDate\":\""
            + txnDate
            + "\",\"categoryId\":\""
            + diningId
            + "\",\"clientRef\":\""
            + UUID.randomUUID()
            + "\"}";

    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/transactions", HttpMethod.POST, as(user, payload), String.class);
    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Setup failed creating a transaction: "
            + response.getStatusCode()
            + " "
            + response.getBody());
  }

  private void setBudget(UUID user, String overallLimit) {
    String payload =
        "{\"period\":\""
            + YearMonth.now()
            + "\",\"overallLimit\":"
            + overallLimit
            + ",\"currency\":\"LKR\"}";

    ResponseEntity<String> response =
        rest.exchange("/api/insights/budget", HttpMethod.PUT, as(user, payload), String.class);
    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Setup failed setting a budget: " + response.getStatusCode() + " " + response.getBody());
  }

  private void saveAccount(UUID user) {
    accounts.save(
        BiAccount.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .bankCode("TESTBANK")
            .instrumentType(InstrumentType.CARD)
            .maskedTail("4242")
            .displayName(ACCOUNT_LABEL)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build());
  }

  private void saveAlert(UUID user) {
    alerts.save(
        BiBudgetAlert.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .periodStart(LocalDate.now().withDayOfMonth(1))
            .categoryId(diningId)
            .alertType(BudgetAlertType.CATEGORY_EXCEEDED)
            .thresholdPct((short) 100)
            .amountAtRaise(new BigDecimal(ALERT_AMOUNT + ".00"))
            .limitAtRaise(new BigDecimal("400000.00"))
            .raisedAt(OffsetDateTime.now())
            .build());
  }
}
