package com.moneyreserve.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.MatchType;
import com.moneyreserve.service.calc.MerchantRuleBuilder.Level;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The merchant-rule form ↔ stored rule mapping (architecture doc §3.5, §7.5.2). */
@DisplayName("MerchantRuleBuilder")
class MerchantRuleBuilderTest {

  private static MerchantRuleBuilder.Generated gen(String type, String... names) {
    return MerchantRuleBuilder.generate(type, List.of(names));
  }

  @Test
  @DisplayName("EXACT / PREFIX / REGEX × one / several names generate the documented stored rule")
  void generation() {
    // 'Contains' (REGEX in the form) is stored for the keyword engine.
    assertEquals(new MerchantRuleBuilder.Generated(MatchType.TERMS, "CARGILLS FOOD CITY"), gen("REGEX", "CARGILLS FOOD CITY"));
    assertEquals(new MerchantRuleBuilder.Generated(MatchType.TERMS, "FUEL|PETROL"), gen("REGEX", "FUEL", "PETROL"));
    assertEquals(
        new MerchantRuleBuilder.Generated(MatchType.TERMS, "GROCER*|FOOD CITY|-SURCHARGE"),
        gen("REGEX", "GROCER*", "FOOD CITY", "-SURCHARGE"));
    assertEquals(new MerchantRuleBuilder.Generated(MatchType.PREFIX, "UBER"), gen("PREFIX", "UBER"));
    assertEquals(new MerchantRuleBuilder.Generated(MatchType.REGEX, "^(?:UBER|PICKME)"), gen("PREFIX", "UBER", "PICKME"));
    assertEquals(new MerchantRuleBuilder.Generated(MatchType.EXACT, "DIALOG AXIATA"), gen("EXACT", "DIALOG AXIATA"));
    assertEquals(
        new MerchantRuleBuilder.Generated(MatchType.REGEX, "^(?:DIALOG AXIATA|MOBITEL)$"), gen("EXACT", "DIALOG AXIATA", "MOBITEL"));
  }

  @Test
  @DisplayName("keywords are stored as typed; the old \\bA\\b form still reads back as names")
  void escaping() {
    assertEquals("ST. JOHN'S|M&S", gen("REGEX", "ST. JOHN'S", "M&S").pattern());
    assertEquals(
        List.of("ST. JOHN'S", "M&S"),
        MerchantRuleBuilder.derive(MatchType.REGEX, "\\bST\\. JOHN'S\\b|\\bM&S\\b").orElseThrow().names());
    assertEquals("REGEX", MerchantRuleBuilder.derive(MatchType.TERMS, "FUEL|GROCER*").orElseThrow().matchType());
  }

  @Test
  @DisplayName("every generated pattern reads back as the same form")
  void roundTrip() {
    for (List<String> names : List.of(List.of("FUEL", "PETROL"), List.of("UBER", "PICKME"), List.of("DIALOG AXIATA"))) {
      for (String type : List.of("REGEX", "PREFIX", "EXACT")) {
        MerchantRuleBuilder.Generated g = MerchantRuleBuilder.generate(type, names);
        MerchantRuleBuilder.Derived d = MerchantRuleBuilder.derive(g.matchType(), g.pattern()).orElseThrow();
        assertEquals(type, d.matchType(), g.toString());
        assertEquals(names, d.names(), g.toString());
      }
    }
  }

  @Test
  @DisplayName("seed patterns the form cannot express are advanced-only")
  void seedPatterns() {
    assertEquals(
        List.of("FOOD", "KITCHEN", "PIZZA", "BURGER"),
        MerchantRuleBuilder.derive(MatchType.REGEX, "\\bFOOD\\b|\\bKITCHEN\\b|\\bPIZZA\\b|\\bBURGER\\b").orElseThrow().names());
    assertTrue(MerchantRuleBuilder.derive(MatchType.REGEX, "\\bHOTEL\\b.*\\bDINING\\b|\\bCANTEEN\\b|\\bDELI\\b").isEmpty());
    assertTrue(MerchantRuleBuilder.derive(MatchType.REGEX, "\\bSUPERMARKET\\b|\\bGROCER|\\bMINI MART\\b").isEmpty());
  }

  @Test
  @DisplayName("name rules (E.021)")
  void nameRules() {
    assertFalse(MerchantRuleBuilder.nameProblems("REGEX", List.of()).isEmpty());
    assertFalse(MerchantRuleBuilder.nameProblems("EXACT", List.of("A")).isEmpty());
    assertTrue(MerchantRuleBuilder.nameProblems("REGEX", List.of("MB")).isEmpty());
    assertTrue(MerchantRuleBuilder.nameProblems("EXACT", List.of("AB")).isEmpty());
    assertFalse(MerchantRuleBuilder.nameProblems("REGEX", List.of("A|B")).isEmpty());
    assertFalse(MerchantRuleBuilder.nameProblems("EXACT", List.of("GROCER*")).isEmpty());
    assertFalse(MerchantRuleBuilder.nameProblems("REGEX", List.of("GR*")).isEmpty());
    assertFalse(MerchantRuleBuilder.nameProblems("REGEX", List.of("-ROAD")).isEmpty());
    List<String> tooLong = new java.util.ArrayList<>();
    for (int i = 0; i < 11; i++) {
      tooLong.add("N".repeat(60));
    }
    assertFalse(MerchantRuleBuilder.nameProblems("REGEX", tooLong).isEmpty());
    assertEquals(List.of("KEELLS SUPER"), MerchantRuleBuilder.normalise(List.of(" keells  super ", "KEELLS SUPER", "")));
  }

  @Test
  @DisplayName("priority levels ↔ bands: High 5, Normal = category block or 150, Low 500")
  void priority() {
    assertEquals(5, MerchantRuleBuilder.priorityFor(Level.HIGH, 30));
    assertEquals(30, MerchantRuleBuilder.priorityFor(Level.NORMAL, 30));
    assertEquals(150, MerchantRuleBuilder.priorityFor(Level.NORMAL, null));
    assertEquals(500, MerchantRuleBuilder.priorityFor(Level.LOW, 30));
    assertEquals(Level.HIGH, MerchantRuleBuilder.level(9));
    assertEquals(Level.NORMAL, MerchantRuleBuilder.level(10));
    assertEquals(Level.NORMAL, MerchantRuleBuilder.level(199));
    assertEquals(Level.LOW, MerchantRuleBuilder.level(200));
  }

  @Test
  @DisplayName("Advanced patterns: portable subset (E.013)")
  void advancedPatterns() {
    assertTrue(MerchantRuleBuilder.patternProblems("REGEX", "\\bHOTEL\\b.*\\bBAR\\b").isEmpty());
    for (String bad : List.of("(?i)abc", "(?<=A)B", "(A)\\1", "A++", "(abc", "A".repeat(1001))) {
      assertFalse(MerchantRuleBuilder.patternProblems("REGEX", bad).isEmpty(), bad);
    }
  }
}
