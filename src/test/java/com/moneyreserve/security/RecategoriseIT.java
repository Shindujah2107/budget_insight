package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiMerchantCategoryMap;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.MatchType;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiMerchantCategoryMapRepository;
import com.moneyreserve.repository.BiRecategoriseMoveRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.CategorisationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Admin "Re-sort old transactions" (V1.31.0): the preview counts only payments the app sorted by
 * itself; Apply moves them and logs each move; Undo puts back the ones still where the run left
 * them; customers can't call any of it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Admin: re-sort old transactions")
class RecategoriseIT {

  private static final String BASE = "/api/insights/admin/recategorise";

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiMerchantCategoryMapRepository rules;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiRecategoriseMoveRepository moves;
  @Autowired private CategorisationService categorisation;

  private UUID from;
  private UUID to;
  private UUID cash;

  @BeforeEach
  void seed() {
    from = ensure("RESORT_FROM", "Resort from");
    to = ensure("RESORT_TO", "Resort to");
    cash = ensure(BiCategory.CASH_WITHDRAWAL_CODE, "Cash withdrawal");
    ensure(BiCategory.OTHER_CODE, "Other");
    UUID ruleId = UUID.nameUUIDFromBytes("resort-rule".getBytes());
    if (rules.findById(ruleId).isEmpty()) {
      rules.save(
          BiMerchantCategoryMap.builder()
              .id(ruleId)
              .merchantPattern("RESORTMART")
              .matchType(MatchType.TERMS)
              .categoryId(to)
              .priority((short) 7)
              .isActive(true)
              .ruleSpec("{\"matchType\":\"REGEX\",\"names\":[\"RESORTMART\"],\"priorityLevel\":\"NORMAL\"}")
              .createdAt(OffsetDateTime.now())
              .updatedAt(OffsetDateTime.now())
              .build());
    }
    categorisation.refresh();
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
                            .iconKey("ic_other")
                            .sortOrder((short) 90)
                            .isActive(true)
                            .createdAt(OffsetDateTime.now())
                            .build())
                    .getId());
  }

  private BiTransaction txn(UUID user, UUID category, String merchant, Consumer<BiTransaction> tweak) {
    BiTransaction t =
        BiTransaction.builder()
            .userId(user)
            .amount(new BigDecimal("1200.00"))
            .currency("LKR")
            .direction(TxnDirection.DEBIT)
            .merchant(merchant)
            .txnDate(LocalDate.now().minusDays(3))
            .txnTimestamp(OffsetDateTime.now())
            .categoryId(category)
            .autoCategoryId(category)
            .isUserCategorised(false)
            .categorySource(CategorySource.RULE)
            .source(TxnSource.MANUAL)
            .idempotencyKey("resort-" + UUID.randomUUID())
            .build();
    tweak.accept(t);
    return transactions.save(t);
  }

  private JsonNode group(JsonNode preview) {
    for (JsonNode g : preview.path("groups")) {
      if ("RESORT_FROM".equals(g.path("from").path("code").asText()) && "RESORT_TO".equals(g.path("to").path("code").asText())) {
        return g;
      }
    }
    return null;
  }

  @Test
  @DisplayName("preview → apply → undo: only auto-sorted payments move, each move is logged, undo puts them back once")
  void previewApplyUndo() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    UUID user = UUID.randomUUID();
    BiTransaction moves1 = txn(user, from, "RESORTMART NUGEGODA", t -> {});
    BiTransaction moves2 = txn(user, from, "RESORTMART KANDY", t -> t.setCategorySource(CategorySource.FALLBACK));
    BiTransaction chosen = txn(user, from, "RESORTMART COLOMBO", t -> {
      t.setIsUserCategorised(true);
      t.setCategorySource(CategorySource.USER);
    });
    BiTransaction noRule = txn(user, from, "SOMEWHERE ELSE", t -> {});
    BiTransaction atm = txn(user, cash, "RESORTMART ATM", t -> {});
    BiTransaction moved = txn(user, from, "RESORTMART", t -> t.setMovement("OWN_ACCOUNT"));

    ResponseEntity<String> p = admin.call(HttpMethod.GET, BASE + "/preview", null);
    assertEquals(200, p.getStatusCode().value(), p.getBody());
    JsonNode g = group(AdminTestSupport.data(p));
    assertTrue(g != null, p.getBody());
    assertEquals(2, g.path("payments").asLong(), "only the two auto-sorted RESORTMART payments");
    assertEquals(1, g.path("customers").asLong());
    assertTrue(AdminTestSupport.data(p).path("userSorted").asLong() >= 1);

    ResponseEntity<String> applied = admin.call(HttpMethod.POST, BASE + "/apply", null);
    assertEquals(200, applied.getStatusCode().value(), applied.getBody());
    String runId = AdminTestSupport.data(applied).path("id").asText();
    assertTrue(AdminTestSupport.data(applied).path("moved").asInt() >= 2);

    assertEquals(to, transactions.findById(moves1.getId()).orElseThrow().getCategoryId());
    BiTransaction m2 = transactions.findById(moves2.getId()).orElseThrow();
    assertEquals(to, m2.getCategoryId());
    assertEquals(CategorySource.RULE, m2.getCategorySource());
    assertEquals(from, transactions.findById(chosen.getId()).orElseThrow().getCategoryId(), "a person's choice is never moved");
    assertEquals(from, transactions.findById(noRule.getId()).orElseThrow().getCategoryId(), "nothing matched: stays");
    assertEquals(cash, transactions.findById(atm.getId()).orElseThrow().getCategoryId(), "cash withdrawals stay");
    assertEquals(from, transactions.findById(moved.getId()).orElseThrow().getCategoryId(), "money moves stay");
    assertTrue(moves.findAllByRunId(UUID.fromString(runId)).stream().anyMatch(m -> m.getTransactionId().equals(moves1.getId()) && m.getFromCategoryId().equals(from)));
    assertTrue(group(AdminTestSupport.data(admin.call(HttpMethod.GET, BASE + "/preview", null))) == null, "nothing left to move");

    // The customer re-sorts one of them by hand before the undo: theirs now, so it is skipped.
    BiTransaction mine = transactions.findById(moves1.getId()).orElseThrow();
    mine.setIsUserCategorised(true);
    mine.setCategorySource(CategorySource.USER);
    transactions.save(mine);

    ResponseEntity<String> undone = admin.call(HttpMethod.POST, BASE + "/runs/" + runId + "/undo", null);
    assertEquals(200, undone.getStatusCode().value(), undone.getBody());
    assertTrue(AdminTestSupport.data(undone).path("skipped").asInt() >= 1);
    BiTransaction back = transactions.findById(moves2.getId()).orElseThrow();
    assertEquals(from, back.getCategoryId(), "undo puts it back");
    assertEquals(CategorySource.FALLBACK, back.getCategorySource(), "with the source it had");
    assertEquals(to, transactions.findById(moves1.getId()).orElseThrow().getCategoryId(), "changed since: left alone");

    ResponseEntity<String> again = admin.call(HttpMethod.POST, BASE + "/runs/" + runId + "/undo", null);
    assertEquals(409, again.getStatusCode().value());
    assertTrue(again.getBody().contains("MR.ADM.E.024"), again.getBody());

    JsonNode runs = AdminTestSupport.data(admin.call(HttpMethod.GET, BASE + "/runs", null));
    boolean listed = false;
    for (JsonNode r : runs) {
      listed |= runId.equals(r.path("id").asText()) && !r.path("undoneAt").isNull();
    }
    assertTrue(listed, runs.toString());
  }

  @Test
  @DisplayName("customers can't preview, apply or undo")
  void customersRefused() {
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(UUID.randomUUID()));
    for (String[] call : new String[][] {{"GET", "/preview"}, {"POST", "/apply"}, {"GET", "/runs"}, {"POST", "/runs/" + UUID.randomUUID() + "/undo"}}) {
      ResponseEntity<String> r = rest.exchange(BASE + call[1], HttpMethod.valueOf(call[0]), new HttpEntity<>(null, h), String.class);
      assertEquals(403, r.getStatusCode().value(), call[1] + " " + r.getBody());
    }
  }
}
