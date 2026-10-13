package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * One customer's money is invisible to another.
 *
 * <p><b>This is the whole of authorisation in this service.</b> There are no scopes and no roles
 * to lean on — the gateway says who the caller is, and every repository query is scoped by that
 * user id. A finder that forgot its owner would not fail, throw, or log anything; it would quietly
 * return somebody else's bank transactions to a customer who asked for their own. Nothing else in
 * the test suite would notice, because every other test uses one user.
 *
 * <p>The refusal to look for is <b>404, not 403</b>. Ownership scoping means another customer's
 * transaction is not a resource this caller may not touch; it is a resource that does not exist as
 * far as their queries are concerned. A 403 would confirm the row exists, which is itself a
 * disclosure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Ownership isolation between customers")
class OwnershipIsolationIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private ObjectMapper objectMapper;

  private static final String TRANSACTIONS = "/api/insights/transactions";
  private static final String TOKEN = "integration-test-gateway-token";

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  private UUID diningId;

  @BeforeEach
  void seedReferenceData() {
    // Flyway is off on the test profile and Hibernate creates empty tables, so the categories the
    // categoriser needs have to be put there. OTHER is not optional: every create resolves the
    // automatic category even when the customer chose one, and the categoriser refuses to start
    // without its fallback.
    diningId = ensure("DINING_OUT", "Dining out");
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

  private HttpEntity<String> as(UUID user, String body) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    h.add("X-Gateway-Token", TOKEN);
    h.add("X-User-Id", user.toString());
    h.add("X-Tenant", UUID.randomUUID().toString());
    return new HttpEntity<>(body, h);
  }

  /** Creates a transaction for {@code user} and returns its id. */
  private UUID createTransaction(UUID user, String amount) throws Exception {
    String body =
        "{\"amount\":"
            + amount
            + ",\"currency\":\"LKR\",\"direction\":\"DEBIT\",\"merchant\":\"Test Cafe\","
            + "\"txnDate\":\""
            + LocalDate.now()
            + "\",\"categoryId\":\""
            + diningId
            + "\",\"clientRef\":\""
            + UUID.randomUUID()
            + "\"}";

    ResponseEntity<String> response =
        rest.exchange(TRANSACTIONS, HttpMethod.POST, as(user, body), String.class);

    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Setup failed: could not create a transaction, got "
            + response.getStatusCode()
            + " "
            + response.getBody());

    JsonNode node = objectMapper.readTree(response.getBody());
    JsonNode id = node.path("data").path("transaction").path("id");
    assertFalse(id.isMissingNode(), "No transaction id in " + response.getBody());
    return UUID.fromString(id.asText());
  }

  @Test
  @DisplayName("A transaction one customer created cannot be edited by another")
  void anotherCustomerCannotEdit() throws Exception {
    UUID aliceTxn = createTransaction(alice, "1500.00");

    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + aliceTxn,
            HttpMethod.PATCH,
            as(bob, "{\"amount\":9999.00}"),
            String.class);

    assertEquals(404, response.getStatusCode().value(), "Bob must not be able to edit Alice's row");
  }

  @Test
  @DisplayName("A transaction one customer created cannot be deleted by another")
  void anotherCustomerCannotDelete() throws Exception {
    UUID aliceTxn = createTransaction(alice, "2500.00");

    ResponseEntity<String> response =
        rest.exchange(TRANSACTIONS + "/" + aliceTxn, HttpMethod.DELETE, as(bob, null), String.class);

    assertEquals(
        404, response.getStatusCode().value(), "Bob must not be able to delete Alice's row");

    // And it must still be there afterwards: a refusal that deleted the row anyway would satisfy
    // the status assertion and lose a customer's data.
    ResponseEntity<String> aliceStillHasIt =
        rest.exchange(
            TRANSACTIONS + "/" + aliceTxn,
            HttpMethod.PATCH,
            as(alice, "{\"amount\":2500.00}"),
            String.class);
    assertTrue(
        aliceStillHasIt.getStatusCode().is2xxSuccessful(),
        "Alice's transaction should have survived Bob's attempt; got "
            + aliceStillHasIt.getStatusCode());
  }

  @Test
  @DisplayName("The breakdown shows a customer only their own spend")
  void breakdownIsScopedToTheCaller() throws Exception {
    createTransaction(alice, "7777.00");

    ResponseEntity<String> bobsBreakdown =
        rest.exchange("/api/insights/breakdown", HttpMethod.GET, as(bob, null), String.class);

    assertTrue(bobsBreakdown.getStatusCode().is2xxSuccessful());
    assertNotNull(bobsBreakdown.getBody());
    // The amount is distinctive on purpose: if ownership scoping were missing anywhere in the
    // aggregate query, Alice's figure would surface here.
    assertFalse(
        bobsBreakdown.getBody().contains("7777"),
        "Alice's spend appeared in Bob's breakdown: " + bobsBreakdown.getBody());
  }

  @Test
  @DisplayName("Each customer's own transaction remains reachable to them")
  void ownerStillHasAccess() throws Exception {
    // The control. Without it, every assertion above would pass against a service that refuses
    // everyone equally.
    UUID bobTxn = createTransaction(bob, "3300.00");

    ResponseEntity<String> response =
        rest.exchange(
            TRANSACTIONS + "/" + bobTxn,
            HttpMethod.PATCH,
            as(bob, "{\"amount\":3400.00}"),
            String.class);

    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Bob must be able to edit his own row; got " + response.getStatusCode());
  }
}
