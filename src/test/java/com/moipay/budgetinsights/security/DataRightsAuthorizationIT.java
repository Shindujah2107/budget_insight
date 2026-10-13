package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
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
 * The two most dangerous routes in the service, from the authorisation side.
 *
 * <p>Export hands over a customer's entire financial history; erasure destroys it. An ownership
 * mistake in either is not a defect, it is an incident. These assertions were written in Phase 2,
 * before either endpoint did anything, so that they would be written by someone who did not yet
 * know how the implementation scopes its queries.
 *
 * <p>They are no longer vacuous. Until R7 both routes answered 501, so Bob's erasure could not
 * reach Alice's data because it reached nothing at all — stated at the time rather than left for a
 * reader to discover. Both now fulfil, the assertions are unchanged, and each has gained the
 * control that could not be written while the routes did nothing: that Alice's own erasure really
 * does remove Alice's data, and that her own export really does contain it. Without those, every
 * test here would still pass against a pair of endpoints that refused everyone.
 *
 * <p>{@link DataRightsIT} covers completeness — every table exported, every table cleared. This
 * file covers only who may do it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Data rights authorisation")
class DataRightsAuthorizationIT {

  private static final String TOKEN = "integration-test-gateway-token";
  private static final String EXPORT = "/api/insights/data/export";
  private static final String ERASURE = "/api/insights/data";
  private static final String CONSENT = "/api/insights/consent";
  private static final String BREAKDOWN = "/api/insights/breakdown";

  /** Alice's, and distinctive enough that finding it in Bob's response is proof, not a guess. */
  private static final String SPEND = "736451";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();

  private UUID diningId;

  @BeforeEach
  void seedAlicesSpend() {
    diningId = ensureCategory("DINING_OUT", "Dining out");
    ensureCategory(BiCategory.OTHER_CODE, "Other");
    createTransaction(alice, SPEND + ".00");
  }

  // ---------------------------------------------------------------------------------
  // Erasure
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("one customer's erasure request leaves another's data untouched")
  void erasureDoesNotReachAnotherCustomer() {
    rest.exchange(ERASURE, HttpMethod.DELETE, as(bob, null), String.class);

    ResponseEntity<String> alicesBreakdown =
        rest.exchange(BREAKDOWN, HttpMethod.GET, as(alice, null), String.class);

    assertTrue(alicesBreakdown.getStatusCode().is2xxSuccessful());
    assertTrue(
        alicesBreakdown.getBody() != null && alicesBreakdown.getBody().contains(SPEND),
        "Bob's erasure took Alice's spend with it: " + alicesBreakdown.getBody());
  }

  @Test
  @DisplayName("a customer's own erasure does remove their data")
  void erasureRemovesTheCallersOwnData() {
    // The control that could not exist while erasure answered 501. Without it the test above
    // passes against an erasure that erases nothing at all.
    rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class);

    ResponseEntity<String> alicesBreakdown =
        rest.exchange(BREAKDOWN, HttpMethod.GET, as(alice, null), String.class);

    assertTrue(alicesBreakdown.getStatusCode().is2xxSuccessful());
    assertFalse(
        alicesBreakdown.getBody() != null && alicesBreakdown.getBody().contains(SPEND),
        "Alice's spend survived her own erasure: " + alicesBreakdown.getBody());
  }

  // ---------------------------------------------------------------------------------
  // Export
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("one customer's export never carries another's figures")
  void exportDoesNotCarryAnotherCustomersData() {
    ResponseEntity<String> requested =
        rest.exchange(EXPORT, HttpMethod.POST, as(bob, "{}"), String.class);
    assertTrue(requested.getStatusCode().is2xxSuccessful());

    String bobsDocument = download(bob, requested.getBody());

    assertFalse(
        bobsDocument.contains(SPEND),
        "Alice's spend appeared in Bob's export: " + bobsDocument);
  }

  @Test
  @DisplayName("a customer's own export does carry their figures")
  void exportCarriesTheCallersOwnData() {
    // The other control. An export that was empty for everyone would satisfy the test above.
    ResponseEntity<String> requested =
        rest.exchange(EXPORT, HttpMethod.POST, as(alice, "{}"), String.class);
    assertTrue(requested.getStatusCode().is2xxSuccessful());

    assertTrue(
        download(alice, requested.getBody()).contains(SPEND),
        "Alice's own export did not contain her own spend");
  }

  @Test
  @DisplayName("export and erasure are fulfilled, not answering 501")
  void theDataRightsRoutesAreFulfilled() {
    // ---- THE LINE PHASE 2 SAID WOULD CHANGE HERE ----
    // It pinned 501 for both routes and was marked as the single assertion that fulfilment would
    // rewrite. Export answers 202 because the document is prepared and then fetched; erasure
    // answers 200 because it is done by the time it replies.
    assertEquals(
        202,
        rest.exchange(EXPORT, HttpMethod.POST, as(alice, "{}"), String.class)
            .getStatusCode()
            .value());
    assertEquals(
        200,
        rest.exchange(ERASURE, HttpMethod.DELETE, as(alice, null), String.class)
            .getStatusCode()
            .value());
  }

  // ---------------------------------------------------------------------------------
  // Consent
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("consent is recorded per customer, not per service")
  void consentIsPerCustomer() {
    String grant =
        "{\"consentVersion\":\"1.0\",\"policyHash\":\"isolation-test\",\"channel\":\"ANDROID\"}";
    ResponseEntity<String> granted =
        rest.exchange(CONSENT, HttpMethod.POST, as(alice, grant), String.class);
    assertTrue(
        granted.getStatusCode().is2xxSuccessful(),
        "Setup failed granting consent: " + granted.getStatusCode() + " " + granted.getBody());

    ResponseEntity<String> alices =
        rest.exchange(CONSENT, HttpMethod.GET, as(alice, null), String.class);
    ResponseEntity<String> bobs =
        rest.exchange(CONSENT, HttpMethod.GET, as(bob, null), String.class);

    assertTrue(
        alices.getBody() != null && alices.getBody().contains("\"consentGiven\":true"),
        "Alice's own consent did not read back as given: " + alices.getBody());
    assertFalse(
        bobs.getBody() != null && bobs.getBody().contains("\"consentGiven\":true"),
        "Alice's consent was reported as Bob's: " + bobs.getBody());
  }

  // ---------------------------------------------------------------------------------

  /** Pulls the export id out of the request receipt and fetches the document. */
  private String download(UUID user, String receipt) {
    String marker = "\"exportId\":\"";
    int at = receipt == null ? -1 : receipt.indexOf(marker);
    assertTrue(at >= 0, "No exportId in the export receipt: " + receipt);
    int from = at + marker.length();
    String exportId = receipt.substring(from, receipt.indexOf('"', from));

    ResponseEntity<String> downloaded =
        rest.exchange(EXPORT + "/" + exportId, HttpMethod.GET, as(user, null), String.class);
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

  private void createTransaction(UUID user, String amount) {
    String payload =
        "{\"amount\":"
            + amount
            + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Data Rights Cafe\","
            + "\"txnDate\":\""
            + LocalDate.now()
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
}
