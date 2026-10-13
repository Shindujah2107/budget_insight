package com.moipay.budgetinsights.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiMerchantCategoryMap;
import com.moipay.budgetinsights.domain.MatchType;
import com.moipay.budgetinsights.domain.TxnDirection;
import com.moipay.budgetinsights.service.CategorisationInput;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiMerchantCategoryMapRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Rule evaluation order and the fall-through to OTHER.
 *
 * <p>These matter because they are silent when wrong. A rule that never fires, or one that fires
 * ahead of a more specific rule, produces a transaction filed under the wrong category — a screen
 * that is confidently incorrect rather than visibly broken.
 */
@ExtendWith(MockitoExtension.class)
class CategorisationServiceImplTest {

  private static final UUID TRANSPORT = UUID.randomUUID();
  private static final UUID DINING = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  @Mock private BiMerchantCategoryMapRepository merchantMaps;
  @Mock private BiCategoryRepository categories;
  @InjectMocks private CategorisationServiceImpl service;

  private final List<BiMerchantCategoryMap> rules = new ArrayList<>();

  private static BiCategory category(UUID id, String code, boolean active) {
    return BiCategory.builder().id(id).code(code).nameEn(code).iconKey("ic").isActive(active).build();
  }

  private BiMerchantCategoryMap rule(String pattern, MatchType type, UUID categoryId, int priority) {
    return BiMerchantCategoryMap.builder()
        .id(UUID.randomUUID())
        .merchantPattern(pattern)
        .matchType(type)
        .categoryId(categoryId)
        .priority((short) priority)
        .isActive(true)
        .build();
  }

  @BeforeEach
  void setUp() {
    rules.clear();
    lenient().when(merchantMaps.findAllByIsActiveTrueOrderByPriorityAsc()).thenReturn(rules);
    lenient()
        .when(categories.findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE))
        .thenReturn(Optional.of(category(OTHER, "OTHER", true)));
    lenient().when(categories.findById(TRANSPORT)).thenReturn(Optional.of(category(TRANSPORT, "TRANSPORT", true)));
    lenient().when(categories.findById(DINING)).thenReturn(Optional.of(category(DINING, "DINING_OUT", true)));
  }

  @Test
  @DisplayName("a regex rule matches a substring, case-insensitively")
  void regexMatches() {
    rules.add(rule("\\bFUEL\\b", MatchType.REGEX, TRANSPORT, 10));
    assertEquals("TRANSPORT", service.categorise("Ceypetco Fuel").getCode());
  }

  @Test
  @DisplayName("word boundaries stop a rule matching inside a longer word")
  void wordBoundaryHolds() {
    rules.add(rule("\\bBUS\\b", MatchType.REGEX, TRANSPORT, 10));
    assertEquals("OTHER", service.categorise("BUSINESS SUPPLIES").getCode());
  }

  @Test
  @DisplayName("nothing matches, so OTHER — never null, never an exception")
  void fallsThroughToOther() {
    rules.add(rule("\\bFUEL\\b", MatchType.REGEX, TRANSPORT, 10));
    assertEquals("OTHER", service.categorise("SOME UNKNOWN SHOP").getCode());
  }

  @Test
  @DisplayName("cash has no merchant, and cash is OTHER")
  void nullMerchant() {
    assertEquals("OTHER", service.categorise((String) null).getCode());
    assertEquals("OTHER", service.categorise("   ").getCode());
  }

  @Test
  @DisplayName("lower priority wins, whatever order the repository returned")
  void priorityOrdering() {
    rules.add(rule("\\bCAFE\\b", MatchType.REGEX, TRANSPORT, 90));
    rules.add(rule("\\bCAFE\\b", MatchType.REGEX, DINING, 10));
    assertEquals("DINING_OUT", service.categorise("HARBOUR CAFE").getCode());
  }

  @Test
  @DisplayName("at equal priority, EXACT beats PREFIX beats REGEX")
  void matchTypeOrderingAtEqualPriority() {
    rules.add(rule("KEELLS", MatchType.REGEX, TRANSPORT, 50));
    rules.add(rule("KEELLS SUPER", MatchType.EXACT, DINING, 50));
    assertEquals("DINING_OUT", service.categorise("Keells Super").getCode());
  }

  @Test
  @DisplayName("merchant strings are normalised: case, padding and doubled spaces")
  void normalisation() {
    rules.add(rule("KEELLS SUPER", MatchType.EXACT, DINING, 10));
    assertEquals("DINING_OUT", service.categorise("  keells   super  ").getCode());
  }

  @Test
  @DisplayName("a rule pointing at a retired category is skipped, not returned")
  void inactiveTargetSkipped() {
    when(categories.findById(TRANSPORT)).thenReturn(Optional.of(category(TRANSPORT, "TRANSPORT", false)));
    rules.add(rule("\\bFUEL\\b", MatchType.REGEX, TRANSPORT, 10));
    assertEquals("OTHER", service.categorise("CEYPETCO FUEL").getCode());
  }

  @Test
  @DisplayName("a pattern that will not compile is dropped; the rest still categorise")
  void malformedRegexDoesNotBreakThePass() {
    rules.add(rule("[unclosed", MatchType.REGEX, TRANSPORT, 10));
    rules.add(rule("\\bCAFE\\b", MatchType.REGEX, DINING, 20));
    assertEquals("DINING_OUT", service.categorise("HARBOUR CAFE").getCode());
  }

  @Test
  @DisplayName("a PREFIX rule anchors at the start, not anywhere")
  void prefixAnchors() {
    rules.add(rule("KEELLS", MatchType.PREFIX, DINING, 10));
    assertEquals("DINING_OUT", service.categorise("KEELLS SUPER COLOMBO").getCode());
    assertEquals("OTHER", service.categorise("SUPER KEELLS").getCode());
  }

  @Test
  @DisplayName("the most specific match wins across categories, whatever the priority")
  void mostSpecificWins() {
    rules.add(rule("STORE", MatchType.TERMS, TRANSPORT, 30));
    rules.add(rule("DRUG STORE", MatchType.TERMS, DINING, 40));
    assertEquals("DINING_OUT", service.categorise("ABC Drug-Store").getCode());
  }

  @Test
  @DisplayName("a High-priority rule beats a more specific Normal one")
  void levelBeatsSpecificity() {
    rules.add(rule("ATM", MatchType.TERMS, TRANSPORT, 5));
    rules.add(rule("KANDY BUS STAND", MatchType.TERMS, DINING, 10));
    assertEquals("TRANSPORT", service.categorise("ATM KANDY BUS STAND").getCode());
  }

  @Test
  @DisplayName("layer 0: a configured message type decides without any rule")
  void messageTypeLayer() {
    rules.add(rule("BUS", MatchType.TERMS, TRANSPORT, 10));
    assertEquals(
        "OTHER",
        service
            .categorise(new CategorisationInput("KANDY BUS STAND", "ATM_RECEIPT", TxnDirection.DEBIT))
            .getCode());
    assertEquals(
        "TRANSPORT",
        service
            .categorise(new CategorisationInput("KANDY BUS STAND", "CARD_PURCHASE", TxnDirection.DEBIT))
            .getCode());
  }

  @Test
  @DisplayName("missing OTHER is a broken deployment, and says so")
  void missingFallbackIsLoud() {
    when(categories.findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE)).thenReturn(Optional.empty());
    IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.categorise((String) null));
    assertEquals(true, ex.getMessage().contains("V1.0.1"));
  }
}
