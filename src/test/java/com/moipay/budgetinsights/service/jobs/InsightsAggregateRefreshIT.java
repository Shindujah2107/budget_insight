package com.moipay.budgetinsights.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiInsightCache;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiInsightCacheRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;
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
 * BI-29 — the aggregate cache and the job that warms it.
 *
 * <p>The assertion that matters is {@link #cachedPayloadEqualsFreshlyComputed()}. Everything else
 * here is plumbing; that one is the reason serving reads from the cache is safe at all. If a cached
 * payload can differ from a computed one, the cache is not an optimisation, it is a second source
 * of figures — and the customer sees whichever the clock happened to choose.
 *
 * <p>The invalidation half has worked since R1 and had nothing to invalidate. {@link
 * #aLedgerWriteInvalidatesTheCachedPeriod()} is the first test in the suite where it is doing
 * anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Aggregate cache and refresh")
class InsightsAggregateRefreshIT {

  private static final String TOKEN = "integration-test-gateway-token";
  private static final String BREAKDOWN = "/api/insights/breakdown";

  /** Pinned deliberately: a cache key that changes silently orphans every row written before it. */
  private static final String CACHE_KEY = "breakdown:v1";

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiInsightCacheRepository caches;
  @Autowired private InsightsAggregateRefreshJob job;

  private final UUID user = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();

  private UUID diningId;
  private LocalDate monthStart;
  private LocalDate monthEnd;

  @BeforeEach
  void seedOneMonthOfSpend() {
    diningId = ensureCategory("DINING_OUT", "Dining out");
    ensureCategory(BiCategory.OTHER_CODE, "Other");

    YearMonth month = YearMonth.from(LocalDate.now());
    monthStart = month.atDay(1);
    monthEnd = month.atEndOfMonth();

    createTransaction("4321.00", LocalDate.now());
  }

  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("a cached payload is identical to a freshly computed one")
  void cachedPayloadEqualsFreshlyComputed() throws Exception {
    JsonNode firstRead = breakdown();

    // Written by the read above, and the thing a later release would serve instead of computing.
    JsonNode stored = objectMapper.readTree(cacheRow().orElseThrow().getPayload());
    assertEquals(
        firstRead,
        stored,
        "What was cached is not what the endpoint returned, so serving from the cache would "
            + "change the customer's figures");

    // Now force the computation again with no cache to read, and compare like for like. Only
    // this customer's row: the context is shared with every other integration test in the suite.
    caches.delete(cacheRow().orElseThrow());
    JsonNode recomputed = breakdown();

    assertEquals(
        stored,
        recomputed,
        "The cached payload and a fresh computation of the same period disagree");
  }

  @Test
  @DisplayName("the first read caches the period and the second is served from it")
  void theFirstReadPopulatesTheCache() {
    assertTrue(cacheRow().isEmpty(), "Something cached this period before the test read it");

    breakdown();

    assertTrue(cacheRow().isPresent(), "The read did not populate the cache");
  }

  @Test
  @DisplayName("a ledger write invalidates the cached period")
  void aLedgerWriteInvalidatesTheCachedPeriod() throws Exception {
    breakdown();
    assertTrue(cacheRow().isPresent());

    createTransaction("1111.00", LocalDate.now());

    assertTrue(
        cacheRow().isEmpty(),
        "A write left the cached breakdown in place, so the customer would be shown a total "
            + "that no longer includes what they just added");

    // And the recomputed figure reflects the new row rather than the stale one.
    JsonNode afterWrite = breakdown();
    assertTrue(
        afterWrite.path("total").asText().contains("5432"),
        "The breakdown after the write is not the sum of both transactions: " + afterWrite);
  }

  @Test
  @DisplayName("a custom date range is served but never cached")
  void aCustomRangeIsNotCached() {
    // Otherwise one customer with a date picker can write a row per range they try, none of which
    // is ever read a second time.
    LocalDate from = monthStart.plusDays(2);
    ResponseEntity<String> response =
        rest.exchange(
            BREAKDOWN + "?from=" + from + "&to=" + monthEnd,
            HttpMethod.GET,
            authed(),
            String.class);

    assertTrue(response.getStatusCode().is2xxSuccessful());
    assertTrue(
        caches
            .findByUserIdAndCacheKeyAndPeriodStartAndPeriodEnd(user, CACHE_KEY, from, monthEnd)
            .isEmpty(),
        "A partial-month range was cached");
  }

  @Test
  @DisplayName("the refresh job warms the cache for a customer who has not opened the screen")
  void theRefreshJobWarmsActiveCustomers() {
    assertTrue(cacheRow().isEmpty(), "The cache was already warm before the job ran");

    int refreshed = job.refreshNow();

    assertNotEquals(0, refreshed, "The refresh job found no active customers to cover");
    assertTrue(
        cacheRow().isPresent(),
        "The refresh job ran but left this customer's current period uncached");
  }

  @Test
  @DisplayName("the scheduled entry point runs the same work through the lock")
  void theLockedEntryPointRuns() {
    job.refresh();

    assertTrue(
        cacheRow().isPresent(),
        "The scheduled method did not reach the refresh; the lock or the wiring is wrong");
  }

  // ---------------------------------------------------------------------------------

  private Optional<BiInsightCache> cacheRow() {
    return caches.findByUserIdAndCacheKeyAndPeriodStartAndPeriodEnd(
        user, CACHE_KEY, monthStart, monthEnd);
  }

  private JsonNode breakdown() {
    ResponseEntity<String> response =
        rest.exchange(BREAKDOWN, HttpMethod.GET, authed(), String.class);
    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Breakdown failed: " + response.getStatusCode() + " " + response.getBody());
    try {
      return objectMapper.readTree(response.getBody()).path("data");
    } catch (Exception ex) {
      throw new IllegalStateException("Unreadable breakdown response: " + response.getBody(), ex);
    }
  }

  private HttpEntity<String> authed() {
    return entity(null);
  }

  private HttpEntity<String> entity(String payload) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add("X-Gateway-Token", TOKEN);
    headers.add("X-User-Id", user.toString());
    headers.add("X-Tenant", tenant.toString());
    return new HttpEntity<>(payload, headers);
  }

  private void createTransaction(String amount, LocalDate txnDate) {
    String payload =
        "{\"amount\":"
            + amount
            + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Cache Cafe\","
            + "\"txnDate\":\""
            + txnDate
            + "\",\"categoryId\":\""
            + diningId
            + "\",\"clientRef\":\""
            + UUID.randomUUID()
            + "\"}";

    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/transactions", HttpMethod.POST, entity(payload), String.class);
    assertFalse(
        response.getStatusCode().isError(),
        "Setup failed creating a transaction: "
            + response.getStatusCode()
            + " "
            + response.getBody());
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
}
