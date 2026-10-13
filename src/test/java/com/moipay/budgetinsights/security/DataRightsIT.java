package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.domain.AuditAction;
import com.moipay.budgetinsights.domain.BiAccount;
import com.moipay.budgetinsights.domain.BiBudget;
import com.moipay.budgetinsights.domain.BiBudgetAlert;
import com.moipay.budgetinsights.domain.BiBudgetCategoryLimit;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiConsent;
import com.moipay.budgetinsights.domain.BiDeviceSyncState;
import com.moipay.budgetinsights.domain.BiInsightCache;
import com.moipay.budgetinsights.domain.BiRawMessage;
import com.moipay.budgetinsights.domain.BiTransaction;
import com.moipay.budgetinsights.domain.BiTransactionAudit;
import com.moipay.budgetinsights.domain.BudgetAlertType;
import com.moipay.budgetinsights.domain.ConsentChannel;
import com.moipay.budgetinsights.domain.InstrumentType;
import com.moipay.budgetinsights.domain.ParseStatus;
import com.moipay.budgetinsights.domain.TxnDirection;
import com.moipay.budgetinsights.domain.TxnSource;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiBudgetAlertRepository;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiBudgetRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiConsentRepository;
import com.moipay.budgetinsights.repository.BiDeviceSyncStateRepository;
import com.moipay.budgetinsights.repository.BiInsightCacheRepository;
import com.moipay.budgetinsights.repository.BiRawMessageRepository;
import com.moipay.budgetinsights.repository.BiTransactionAuditRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
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
 * Export completeness and erasure completeness, against the same seeded customer (BI-27, BI-28).
 *
 * <p>One fixture, deliberately. Both endpoints answer the same question from opposite ends — what
 * is all of this customer's data — and checking them against different fixtures would let each pass
 * while disagreeing about the answer. Here the customer is seeded into every table once, exported,
 * then erased, and both results are measured against that one seeding.
 *
 * <p><b>The list of tables is this test's own.</b> {@code DataRightsServiceImpl} has a list too,
 * and if this test read it, a table missing from the service would be missing from the check that
 * is supposed to catch it. So the list below is written out independently — and {@link
 * #everyEntityIsClassified()} closes the remaining gap by asking the persistence metamodel what
 * entities actually exist, which fails the build when a new one is added and classified nowhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Data rights — export and erasure")
class DataRightsIT {

  private static final String TOKEN = "integration-test-gateway-token";
  private static final String EXPORT = "/api/insights/data/export";
  private static final String ERASURE = "/api/insights/data";

  /** A table holding rows that belong to a customer, and how its rows are attributed to one. */
  private record Owned(String entity, String where) {}

  private static final String BY_USER = "x.userId = :u";

  private static final List<Owned> CUSTOMER_OWNED =
      List.of(
          new Owned("BiAccount", BY_USER),
          new Owned("BiTransaction", BY_USER),
          new Owned(
              "BiTransactionAudit",
              "x.transactionId IN (SELECT t.id FROM BiTransaction t WHERE t.userId = :u)"),
          new Owned("BiBudget", BY_USER),
          new Owned(
              "BiBudgetCategoryLimit",
              "x.budgetId IN (SELECT b.id FROM BiBudget b WHERE b.userId = :u)"),
          new Owned("BiBudgetAlert", BY_USER),
          new Owned("BiConsent", BY_USER),
          new Owned("BiRawMessage", BY_USER),
          new Owned("BiInsightCache", BY_USER),
          new Owned("BiDeviceSyncState", BY_USER),
          new Owned("BiDataExport", BY_USER));

  /**
   * Shared across every customer. Nothing in them is anyone's to export or erase.
   *
   * <p>The last three are the admin dashboard's configuration workflow over those four tables:
   * {@code bi_config_change} (pending drafts), {@code bi_config_changeset} (its revision counter)
   * and {@code bi_reference_release} (published releases with a config-only before/after diff).
   * They hold configuration and the publishing staff member's id, never a customer's id or data,
   * so a customer's export and erasure have nothing to find in them. {@code bi_bank} is the bank
   * registry and {@code bi_budget_policy} the single budget policy row: configuration, no customer
   * data.
   */
  private static final Set<String> REFERENCE_ENTITIES =
      Set.of(
          "BiCategory",
          "BiMerchantCategoryMap",
          "BiSmsSenderAllowlist",
          "BiBankParserTemplate",
          "BiConfigChange",
          "BiConfigChangeset",
          "BiReferenceRelease",
          "BiBank",
          "BiBudgetPolicy",
          // SMS format discovery's word list: shared configuration, no customer data.
          "BiSmsVocabularyWord",
          // The message type list SMS templates pick from (V1.15.0): configuration, no customer data.
          "BiMessageType");

  /**
   * Not customer-scoped at all.
   *
   * <p>{@code bi_compliance_event} has no {@code user_id} column. It records that a detection
   * happened — type, time, source, action, a hashed device id — and never whose it was. That is why
   * it is the evidence an erasure took place rather than something the erasure has to spare.
   */
  private static final Set<String> NOT_CUSTOMER_SCOPED =
      Set.of(
          "BiComplianceEvent",
          // SMS format discovery. A daily count per hashed device and sender, and the shape of a
          // bank's message: neither has a user_id or anything the customer wrote.
          "BiUnmatchedSmsStat",
          "BiSmsFormatCandidate",
          // One row per customer per format, but keyed by an HMAC of the user id, not the id, so
          // it cannot sit in the JPQL list. Erasure removes it through FormatDiscoveryService
          // (removedByTable.formatSamples), and FormatDiscoveryIT asserts that it does.
          "BiSmsFormatSample");

  @PersistenceContext private EntityManager entityManager;

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiAccountRepository accounts;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiTransactionAuditRepository audits;
  @Autowired private BiBudgetRepository budgets;
  @Autowired private BiBudgetCategoryLimitRepository limits;
  @Autowired private BiBudgetAlertRepository alerts;
  @Autowired private BiConsentRepository consents;
  @Autowired private BiRawMessageRepository rawMessages;
  @Autowired private BiInsightCacheRepository caches;
  @Autowired private BiDeviceSyncStateRepository syncStates;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();

  private UUID diningId;

  @BeforeEach
  void seedBothCustomersIntoEveryTable() {
    diningId = ensureCategory("DINING_OUT", "Dining out");
    ensureCategory(BiCategory.OTHER_CODE, "Other");
    seedEverythingFor(alice);
    seedEverythingFor(bob);
  }

  // ---------------------------------------------------------------------------------
  // The classification check — what keeps the two lists above honest
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("every entity in the model is classified as owned, reference or not customer-scoped")
  void everyEntityIsClassified() {
    List<String> unclassified = new ArrayList<>();
    for (EntityType<?> type : entityManager.getMetamodel().getEntities()) {
      String name = type.getName();
      boolean known =
          REFERENCE_ENTITIES.contains(name)
              || NOT_CUSTOMER_SCOPED.contains(name)
              || CUSTOMER_OWNED.stream().anyMatch(o -> o.entity().equals(name));
      if (!known) {
        unclassified.add(name);
      }
    }
    assertTrue(
        unclassified.isEmpty(),
        "A new entity exists that this test says nothing about, so export and erasure are "
            + "unchecked for it. Classify it as customer-owned, reference data, or not "
            + "customer-scoped: "
            + unclassified);
  }

  // ---------------------------------------------------------------------------------
  // Erasure
  // ---------------------------------------------------------------------------------

  @TestFactory
  @DisplayName("erasure leaves no row behind, table by table")
  Stream<DynamicTest> erasureLeavesNoRowBehind() {
    // Proven before the erasure as well as after: a per-table count that was already zero would
    // make the assertion below true without the erasure having done anything.
    for (Owned owned : CUSTOMER_OWNED) {
      if ("BiDataExport".equals(owned.entity())) {
        continue; // Nothing has requested one yet; the export test covers this table.
      }
      assertTrue(
          count(owned, alice) > 0,
          "Setup did not seed " + owned.entity() + ", so erasing it proves nothing");
    }

    ResponseEntity<String> erased =
        rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);
    assertTrue(
        erased.getStatusCode().is2xxSuccessful(),
        "Erasure failed: " + erased.getStatusCode() + " " + erased.getBody());

    return CUSTOMER_OWNED.stream()
        .map(
            owned ->
                DynamicTest.dynamicTest(
                    owned.entity(),
                    () ->
                        assertEquals(
                            0L,
                            count(owned, alice),
                            owned.entity() + " still holds rows for the erased customer")));
  }

  @Test
  @DisplayName("erasing one customer leaves the other's rows exactly as they were")
  void erasureDoesNotTouchAnotherCustomer() {
    List<Long> before = CUSTOMER_OWNED.stream().map(o -> count(o, bob)).toList();

    rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);

    List<Long> after = CUSTOMER_OWNED.stream().map(o -> count(o, bob)).toList();
    assertEquals(before, after, "Alice's erasure changed Bob's row counts");
  }

  @Test
  @DisplayName("erasing twice is not an error and the second time removes nothing")
  void erasureIsIdempotent() {
    rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);

    ResponseEntity<String> second =
        rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);

    assertTrue(
        second.getStatusCode().is2xxSuccessful(),
        "A second erasure was refused: " + second.getStatusCode() + " " + second.getBody());
    assertTrue(
        second.getBody() != null && second.getBody().contains("\"rowsRemoved\":0"),
        "The second erasure reported removing rows: " + second.getBody());
  }

  // ---------------------------------------------------------------------------------
  // Export
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("an export round-trips and carries every table the erasure clears")
  void exportCarriesEveryTable() throws Exception {
    JsonNode document = objectMapper.readTree(requestAndDownload(alice));

    for (Owned owned : CUSTOMER_OWNED) {
      String label = exportLabelFor(owned.entity());
      if (label == null) {
        continue; // Derived or self-referential — see DataRightsServiceImpl.CUSTOMER_DATA.
      }
      assertTrue(document.has(label), "The export has no '" + label + "' section");
      assertTrue(
          document.get(label).isArray() && !document.get(label).isEmpty(),
          "The export's '" + label + "' section is empty, but the table was seeded");
    }
  }

  @Test
  @DisplayName("the export never carries the encrypted text of a bank message")
  void exportNeverCarriesMessageText() throws Exception {
    String document = requestAndDownload(alice);

    assertFalse(
        document.contains(SECRET_CIPHERTEXT),
        "The stored ciphertext of a raw message appeared in the export");
    assertTrue(
        document.contains("\"rawMessages\""),
        "The export omitted raw messages entirely rather than describing them");
  }

  @Test
  @DisplayName("one customer's export carries none of another's rows")
  void exportIsScopedToTheCaller() throws Exception {
    String bobsDocument = requestAndDownload(bob);

    assertFalse(
        bobsDocument.contains(alice.toString()),
        "Alice's user id appeared in Bob's export: " + bobsDocument);
    assertFalse(
        bobsDocument.contains(ALICE_MERCHANT),
        "Alice's transaction appeared in Bob's export: " + bobsDocument);
  }

  @Test
  @DisplayName("an export belonging to another customer cannot be downloaded")
  void anotherCustomersExportIs404() throws Exception {
    UUID exportId = requestExportId(alice);

    ResponseEntity<String> bobsAttempt =
        rest.exchange(EXPORT + "/" + exportId, HttpMethod.GET, as(bob, null), String.class);

    assertEquals(404, bobsAttempt.getStatusCode().value(), "Bob downloaded Alice's export");
  }

  @Test
  @DisplayName("erasure takes the customer's own exports with it")
  void erasureRemovesPreviousExports() throws Exception {
    UUID exportId = requestExportId(alice);

    rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);

    ResponseEntity<String> afterErasure =
        rest.exchange(EXPORT + "/" + exportId, HttpMethod.GET, as(alice, null), String.class);
    assertEquals(
        404,
        afterErasure.getStatusCode().value(),
        "An export survived the erasure, holding the whole history that was just erased");
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /** Maps an entity to its section in the export document, or null when it is not exported. */
  private String exportLabelFor(String entity) {
    return switch (entity) {
      case "BiAccount" -> "accounts";
      case "BiTransaction" -> "transactions";
      case "BiTransactionAudit" -> "transactionAudit";
      case "BiBudget" -> "budgets";
      case "BiBudgetCategoryLimit" -> "budgetCategoryLimits";
      case "BiBudgetAlert" -> "budgetAlerts";
      case "BiConsent" -> "consentHistory";
      case "BiRawMessage" -> "rawMessages";
      case "BiDeviceSyncState" -> "deviceSyncState";
      default -> null;
    };
  }

  private long count(Owned owned, UUID userId) {
    return (Long)
        entityManager
            .createQuery("SELECT COUNT(x) FROM " + owned.entity() + " x WHERE " + owned.where())
            .setParameter("u", userId)
            .getSingleResult();
  }

  private UUID requestExportId(UUID user) throws Exception {
    ResponseEntity<String> requested =
        rest.exchange(EXPORT, HttpMethod.POST, as(user, "{}"), String.class);
    assertTrue(
        requested.getStatusCode().is2xxSuccessful(),
        "Export request failed: " + requested.getStatusCode() + " " + requested.getBody());
    JsonNode id = objectMapper.readTree(requested.getBody()).path("data").path("exportId");
    assertFalse(id.isMissingNode(), "No exportId in " + requested.getBody());
    return UUID.fromString(id.asText());
  }

  private String requestAndDownload(UUID user) throws Exception {
    ResponseEntity<String> downloaded =
        rest.exchange(
            EXPORT + "/" + requestExportId(user), HttpMethod.GET, as(user, null), String.class);
    assertTrue(
        downloaded.getStatusCode().is2xxSuccessful(),
        "Download failed: " + downloaded.getStatusCode() + " " + downloaded.getBody());
    return downloaded.getBody() == null ? "" : downloaded.getBody();
  }

  private HttpEntity<String> as(UUID user, String payload) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Gateway-Token", TOKEN);
    headers.add("X-User-Id", user.toString());
    headers.add("X-Tenant", tenant.toString());
    return new HttpEntity<>(payload, headers);
  }

  private UUID ensureCategory(String code, String name) {
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

  static final String ALICE_MERCHANT = "Erasure Fixture Cafe";
  static final String SECRET_CIPHERTEXT = "ENC::this-must-never-be-exported";

  /**
   * Every customer-owned table, seeded directly.
   *
   * <p>Through the repositories rather than the API because what is under test is whether erasure
   * and export cover the tables, not whether the write endpoints populate them. Going through the
   * API would leave the tables with no endpoint — raw messages, sync state, alerts — unseeded, and
   * those are exactly the ones most likely to be forgotten by an erasure.
   */
  private void seedEverythingFor(UUID user) {
    OffsetDateTime now = OffsetDateTime.now();
    LocalDate today = LocalDate.now();

    UUID accountId =
        accounts
            .save(
                BiAccount.builder()
                    .id(UUID.randomUUID())
                    .userId(user)
                    .tenantId(tenant)
                    .bankCode("FIXTURE")
                    .instrumentType(InstrumentType.CARD)
                    .maskedTail("9911")
                    .displayName("Fixture card")
                    .isActive(true)
                    .createdAt(now)
                    .build())
            .getId();

    UUID transactionId =
        transactions
            .save(
                BiTransaction.builder()
                    .id(UUID.randomUUID())
                    .userId(user)
                    .tenantId(tenant)
                    .accountId(accountId)
                    .amount(new BigDecimal("1234.00"))
                    .currency("LKR")
                    .direction(TxnDirection.DEBIT)
                    .merchant(user.equals(alice) ? ALICE_MERCHANT : "Bob's Cafe")
                    .txnDate(today)
                    .categoryId(diningId)
                    .autoCategoryId(diningId)
                    .isUserCategorised(false)
                    .source(TxnSource.MANUAL)
                    .idempotencyKey(UUID.randomUUID().toString())
                    .createdAt(now)
                    .build())
            .getId();

    audits.save(
        BiTransactionAudit.builder()
            .id(UUID.randomUUID())
            .transactionId(transactionId)
            .action(AuditAction.CREATE)
            .actor("fixture")
            .createdAt(now)
            .build());

    UUID budgetId =
        budgets
            .save(
                BiBudget.builder()
                    .id(UUID.randomUUID())
                    .userId(user)
                    .tenantId(tenant)
                    .periodStart(today.withDayOfMonth(1))
                    .periodEnd(today.withDayOfMonth(today.lengthOfMonth()))
                    .overallLimit(new BigDecimal("50000.00"))
                    .currency("LKR")
                    .createdAt(now)
                    .build())
            .getId();

    limits.save(
        BiBudgetCategoryLimit.builder()
            .id(UUID.randomUUID())
            .budgetId(budgetId)
            .categoryId(diningId)
            .limitAmount(new BigDecimal("10000.00"))
            .alertThresholdPct((short) 80)
            .createdAt(now)
            .build());

    alerts.save(
        BiBudgetAlert.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .tenantId(tenant)
            .periodStart(today.withDayOfMonth(1))
            .categoryId(diningId)
            .alertType(BudgetAlertType.CATEGORY_EXCEEDED)
            .thresholdPct((short) 100)
            .amountAtRaise(new BigDecimal("11000.00"))
            .limitAtRaise(new BigDecimal("10000.00"))
            .raisedAt(now)
            .build());

    consents.save(
        BiConsent.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .tenantId(tenant)
            .consentVersion("1.0")
            .policyHash("fixture-hash")
            .channel(ConsentChannel.ANDROID)
            .grantedAt(now)
            .createdAt(now)
            .build());

    rawMessages.save(
        BiRawMessage.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .tenantId(tenant)
            .bankCode("FIXTURE")
            .ciphertext(SECRET_CIPHERTEXT)
            .parseStatus(ParseStatus.UNPARSED)
            .receivedAt(now)
            .ttlExpiresAt(now.plusDays(7))
            .build());

    caches.save(
        BiInsightCache.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .cacheKey("breakdown:v1")
            .periodStart(today.withDayOfMonth(1))
            .periodEnd(today.withDayOfMonth(today.lengthOfMonth()))
            .payload("{}")
            .computedAt(now)
            .build());

    syncStates.save(
        BiDeviceSyncState.builder()
            .id(UUID.randomUUID())
            .userId(user)
            .deviceId("fixture-device-" + user)
            .lastSyncedAt(now)
            .lastIngestSeq(1L)
            .templateVersion(1)
            .createdAt(now)
            .build());
  }
}
