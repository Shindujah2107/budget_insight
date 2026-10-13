package com.moipay.budgetinsights.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moipay.budgetinsights.domain.MatchType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MerchantTerms (keyword engine)")
class MerchantTermsTest {

  private static Optional<MerchantTerms.Hit> match(String pattern, String merchant) {
    return MerchantTerms.parse(pattern).match(MerchantText.tokens(merchant));
  }

  @Test
  @DisplayName("WORD matches whole words only")
  void word() {
    assertTrue(match("BUS", "SLTB BUS").isPresent());
    assertFalse(match("BUS", "BUSINESS SUPPLIES").isPresent());
    assertFalse(match("GYM", "GYMKHANA CLUB").isPresent());
    assertFalse(match("SPA", "SPAR EXPRESS").isPresent());
  }

  @Test
  @DisplayName("PHRASE matches words in sequence, across punctuation, and joined")
  void phrase() {
    assertTrue(match("WATER BOARD", "NWSDB WATER-BOARD").isPresent());
    assertTrue(match("BILL PAY", "BILLPAY DIALOG").isPresent());
    assertFalse(match("WATER BOARD", "BOARD OF WATER").isPresent());
  }

  @Test
  @DisplayName("STEM matches word beginnings and ranks below a whole word")
  void stem() {
    assertTrue(match("GROCER*", "GROCERY WORLD").isPresent());
    assertTrue(match("PHARMAC*", "PHARMACIES").isPresent());
    assertEquals(5, match("GROCER*", "GROCERS").orElseThrow().specificity());
    assertEquals(10, match("GROCERS", "GROCERS").orElseThrow().specificity());
  }

  @Test
  @DisplayName("=EXACT needs the whole cleaned merchant")
  void exact() {
    assertTrue(match("=LAB", "LAB").isPresent());
    assertFalse(match("=LAB", "LANKA LAB").isPresent());
    assertEquals(1001, match("=LAB", "lab, LK").orElseThrow().specificity());
  }

  @Test
  @DisplayName("-EXCLUDE blocks the whole rule")
  void exclude() {
    assertTrue(match("RENEWAL|-LICENSE", "YOUTUBE PREMIUM RENEWAL").isPresent());
    assertFalse(match("RENEWAL|-LICENSE", "DRIVING LICENSE RENEWAL").isPresent());
    assertFalse(match("BOOKING|-ECHANNEL*", "ECHANNELLING BOOKING").isPresent());
  }

  @Test
  @DisplayName("more words are more specific")
  void specificity() {
    assertEquals(20, match("STORE|DRUG STORE", "ABC DRUG STORE").orElseThrow().specificity());
    assertEquals("DRUG STORE", match("STORE|DRUG STORE", "ABC DRUG STORE").orElseThrow().keyword());
  }

  @Test
  @DisplayName("keyword validation")
  void problems() {
    assertTrue(MerchantTerms.problem("FILLING STATION").isEmpty());
    assertTrue(MerchantTerms.problem("MB").isEmpty());
    assertTrue(MerchantTerms.problem("-SURCHARGE").isEmpty());
    assertTrue(MerchantTerms.problem("GROCER*").isPresent() == false);
    assertTrue(MerchantTerms.problem("A").isPresent());
    assertTrue(MerchantTerms.problem("GR*").isPresent());
    assertTrue(MerchantTerms.problem("=GROCER*").isPresent());
    assertTrue(MerchantTerms.problem("A|B").isPresent());
    assertTrue(MerchantTerms.problem("***").isPresent());
    assertTrue(MerchantTerms.listProblem(List.of("-ROAD")).isPresent());
    assertTrue(MerchantTerms.listProblem(List.of("TEMPLE", "-ROAD")).isEmpty());
  }

  @Test
  @DisplayName("the matcher ranks level, then specificity, then priority")
  void ranking() {
    record R(String name, int priority, String pattern) {}
    List<R> rules =
        List.of(new R("shopping", 30, "STORE"), new R("health", 40, "DRUG STORE"), new R("cash", 5, "ATM"));
    MerchantRuleMatcher m = new MerchantRuleMatcher();
    assertEquals(
        "health",
        m.firstMatch(rules, R::priority, r -> MatchType.TERMS, R::pattern, "ABC DRUG STORE").orElseThrow().name());
    assertEquals(
        "cash",
        m.firstMatch(rules, R::priority, r -> MatchType.TERMS, R::pattern, "ATM DRUG STORE").orElseThrow().name());
  }
}
