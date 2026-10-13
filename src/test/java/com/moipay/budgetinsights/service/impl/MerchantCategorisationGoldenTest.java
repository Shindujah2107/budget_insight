package com.moipay.budgetinsights.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiMerchantCategoryMap;
import com.moipay.budgetinsights.domain.MatchType;
import com.moipay.budgetinsights.domain.TxnDirection;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiMerchantCategoryMapRepository;
import com.moipay.budgetinsights.service.CategorisationInput;
import com.moipay.budgetinsights.service.calc.MigrationRules;
import com.moipay.budgetinsights.service.calc.MigrationRules.Case;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden regression for categorisation: the rules exactly as migration V1.12.0 ships them, run
 * through the real {@link CategorisationServiceImpl} (L0 message type, L1 clean-up, L2–L4 rules,
 * L5 OTHER).
 *
 * <ul>
 *   <li>{@code categorisation/golden.csv}: 118 labelled merchants from real Sri Lankan bank SMS and
 *       the DEV seed catalogue. At least 95% must be exact, and none may land in a <i>wrong</i>
 *       category (a miss may only fall to OTHER, never mislabel spend).
 *   <li>{@code categorisation/false-positives.csv}: traps a naive "contains" rule falls into
 *       (DRUG STORE vs STORE, FOOD CITY vs FOOD, ATM locations, addresses). Every row must be
 *       exact.
 * </ul>
 *
 * <p>Adding keywords should only ever move rows from OTHER to the right category. Add a row here
 * for every new false positive found in production before fixing it.
 */
@DisplayName("Merchant categorisation golden regression (V1.12.0 rules)")
class MerchantCategorisationGoldenTest {

  static final double MIN_ACCURACY = 95.0;

  private static CategorisationServiceImpl service;

  @BeforeAll
  static void setUp() {
    BiMerchantCategoryMapRepository maps = mock(BiMerchantCategoryMapRepository.class);
    BiCategoryRepository categories = mock(BiCategoryRepository.class);
    Map<String, BiCategory> byCode = new HashMap<>();
    List<BiMerchantCategoryMap> rules = new ArrayList<>();
    for (String code :
        List.of(
            "TRANSPORT", "UTILITIES", "HEALTH_CARE", "DEBT_PAYMENTS", "DINING_OUT", "ENTERTAINMENT",
            "PERSONAL_CARE", "SHOPPING", "GIFTS_DONATION", "EDUCATION", "WORK", "TRAVEL", "GYM",
            "SUBSCRIPTIONS", "TRANSFERS", "BILLS_UTILITIES", "OTHER")) {
      BiCategory c =
          BiCategory.builder().id(UUID.randomUUID()).code(code).nameEn(code).iconKey("ic").isActive(true).build();
      byCode.put(code, c);
      lenient().when(categories.findById(c.getId())).thenReturn(Optional.of(c));
      lenient().when(categories.findByCodeAndIsActiveTrue(code)).thenReturn(Optional.of(c));
    }
    for (MigrationRules.Rule r : MigrationRules.load()) {
      rules.add(
          BiMerchantCategoryMap.builder()
              .id(UUID.fromString(r.id()))
              .merchantPattern(r.pattern())
              .matchType(MatchType.TERMS)
              .categoryId(byCode.get(r.categoryCode()).getId())
              .priority((short) r.priority())
              .isActive(true)
              .build());
    }
    assertEquals(15, rules.size(), "V1.12.0 should define 15 rules");
    lenient().when(maps.findAllByIsActiveTrueOrderByPriorityAsc()).thenReturn(rules);
    service = new CategorisationServiceImpl(maps, categories, new InsightsProperties());
  }

  /** The real categoriser over the V1.12.0 rules, with every category active (also used by the DEV report). */
  static CategorisationServiceImpl serviceWithMigratedRules() {
    if (service == null) {
      setUp();
    }
    return service;
  }

  private static String categorise(Case c) {
    TxnDirection direction = c.direction() == null ? null : TxnDirection.valueOf(c.direction());
    return service.categorise(new CategorisationInput(c.merchant(), c.messageType(), direction)).getCode();
  }

  @Test
  @DisplayName("golden set: at least 95% exact and no merchant in a wrong (non-OTHER) category")
  void golden() {
    List<Case> cases = MigrationRules.cases("categorisation/golden.csv");
    int correct = 0;
    List<String> wrong = new ArrayList<>();
    List<String> missed = new ArrayList<>();
    for (Case c : cases) {
      String got = categorise(c);
      if (got.equals(c.expected())) {
        correct++;
      } else if ("OTHER".equals(got)) {
        missed.add(c.merchant() + " (want " + c.expected() + ")");
      } else {
        wrong.add(c.merchant() + " -> " + got + " (want " + c.expected() + ")");
      }
    }
    double accuracy = correct * 100.0 / cases.size();
    String report =
        String.format(
            "%d/%d = %.1f%%%n wrong: %s%n missed: %s", correct, cases.size(), accuracy, wrong, missed);
    assertTrue(cases.size() >= 118, "golden set shrank: " + cases.size());
    assertTrue(wrong.isEmpty(), "wrong category:\n" + report);
    assertTrue(accuracy >= MIN_ACCURACY, "accuracy below " + MIN_ACCURACY + "%:\n" + report);
  }

  static Stream<Case> falsePositives() {
    return MigrationRules.cases("categorisation/false-positives.csv").stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("falsePositives")
  @DisplayName("false-positive traps land exactly where expected")
  void falsePositive(Case c) {
    assertEquals(c.expected(), categorise(c), c.merchant() + " — trap: " + c.note());
  }

  @Test
  @DisplayName("layer 0: an ATM receipt is cash whatever its location says")
  void messageTypeDecides() {
    assertEquals("OTHER", service.categorise(new CategorisationInput("KANDY BUS STAND", "ATM_RECEIPT", TxnDirection.DEBIT)).getCode());
    assertEquals("TRANSPORT", service.categorise(new CategorisationInput("KANDY BUS STAND", "CARD_PURCHASE", TxnDirection.DEBIT)).getCode());
  }

  @Test
  @DisplayName("a refund carries the category of what was refunded")
  void creditKeepsCategory() {
    assertEquals("TRANSPORT", service.categorise(new CategorisationInput("PickMe Ride, LK", "CARD_ALERT", TxnDirection.CREDIT)).getCode());
  }
}
