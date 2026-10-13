package com.moipay.budgetinsights.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SMS format skeleton grammar")
class SkeletonGrammarTest {

  private static final Map<Integer, String> VOCAB =
      Map.of(1, "DEAR", 2, "CUSTOMER", 3, "BILL", 4, "PAYMENT", 9, "LKR", 12, "ON", 13, "AT");

  private static List<String> good() {
    return List.of("1c", "2c", "~,", "3l", "4c", "13l", "W", "9u", "AMT:c2", "12l", "DATE:dd/MM/yy", "TIME:hh:mm a");
  }

  @Test
  @DisplayName("a well-formed skeleton is read, displayed and keyed")
  void readsAWellFormedSkeleton() {
    SkeletonGrammar.Result r = SkeletonGrammar.read(good(), VOCAB);
    assertTrue(r.ok());
    assertEquals(
        "Dear Customer, bill Payment at {W} LKR {AMT} on {DATE:dd/MM/yy} {TIME:hh:mm a}",
        r.skeleton().display());
    assertEquals(String.join("|", good()), r.skeleton().key());
  }

  @Test
  @DisplayName("the synthetic sample uses invented values in the right formats")
  void syntheticSample() {
    SkeletonGrammar.Result r = SkeletonGrammar.read(good(), VOCAB);
    assertEquals(
        "Dear Customer, bill Payment at Sample Text LKR 1,234.56 on 05/03/26 02:35 PM",
        SkeletonGrammar.synthetic(r.skeleton().tokens(), VOCAB));
  }

  @Test
  @DisplayName("tokens that could carry content are refused")
  void refusesContent() {
    for (String bad :
        List.of(
            "JOHN", "Perera", "0771234567", "1234", "12x", "W1", "AMT:1,234.56", "AMT:c5",
            "ACCT:2003XXXX382", "DATE:05/10/26", "DATE:dd/QQ/yy", "TIME:13:51", "N:25", "N:0",
            "{W}", "|", "~~W", "OTP", "NL ", " W", "~", "")) {
      assertNull(SkeletonGrammar.parse(bad), "should not parse: [" + bad + "]");
    }
  }

  @Test
  @DisplayName("every placeholder form in the grammar parses")
  void acceptsTheGrammar() {
    for (String ok :
        List.of(
            "4c", "~4u", "99999l", "W", "~W", "AMT:c0", "AMT:p2", "ACCT:NNNNXXXXNNNN", "ACCT:****NNNN",
            "DATE:dd/MM/yyyy", "DATE:dd Mmmm yyyy", "DATE:Mmm d, yyyy", "DATE:ddMMMyy",
            "DATE:yyyy-MM-dd", "TIME:HH:mm", "TIME:hh:mm:ss a", "TIME:h:mma", "N:1", "N:20",
            "NL", ",", "~.", "~\"", "(", "#")) {
      assertTrue(SkeletonGrammar.parse(ok) != null, "should parse: [" + ok + "]");
    }
  }

  @Test
  @DisplayName("a word id the vocabulary does not have is invalid")
  void unknownWordId() {
    List<String> t = new java.util.ArrayList<>(good());
    t.set(0, "777c");
    assertEquals(SkeletonGrammar.Problem.INVALID, SkeletonGrammar.read(t, VOCAB).problem());
  }

  @Test
  @DisplayName("two unknown-word tokens in a row are invalid (a phone joins them)")
  void doubleUnknown() {
    List<String> t = new java.util.ArrayList<>(good());
    t.add(6, "W");
    assertEquals(SkeletonGrammar.Problem.INVALID, SkeletonGrammar.read(t, VOCAB).problem());
  }

  @Test
  @DisplayName("no amount, or mostly unknown words, is not useful")
  void notUseful() {
    assertEquals(
        SkeletonGrammar.Problem.NOT_USEFUL,
        SkeletonGrammar.read(List.of("1c", "2c", "3l", "4c", "DATE:dd/MM/yy"), VOCAB).problem());
    assertEquals(
        SkeletonGrammar.Problem.NOT_USEFUL,
        SkeletonGrammar.read(List.of("W", "1c", "W", "2c", "W", "AMT:c2", "W", "3l", "W"), VOCAB)
            .problem());
  }

  @Test
  @DisplayName("too short or too long is invalid")
  void size() {
    assertEquals(SkeletonGrammar.Problem.INVALID, SkeletonGrammar.read(List.of("1c", "AMT:c2"), VOCAB).problem());
    List<String> longOne = new java.util.ArrayList<>();
    for (int i = 0; i < 121; i++) {
      longOne.add(i % 2 == 0 ? "1c" : "AMT:c2");
    }
    assertEquals(SkeletonGrammar.Problem.INVALID, SkeletonGrammar.read(longOne, VOCAB).problem());
  }

  @Test
  @DisplayName("an OTP word in the vocabulary would still be caught")
  void otpWordCaught() {
    Map<Integer, String> poisoned = new java.util.HashMap<>(VOCAB);
    poisoned.put(50, "OTP");
    List<String> t = new java.util.ArrayList<>(good());
    t.add("50u");
    assertEquals(SkeletonGrammar.Problem.INVALID, SkeletonGrammar.read(t, poisoned).problem());
  }

  @Test
  @DisplayName("the stored key re-parses to the same tokens, and the hash depends on the sender")
  void keyRoundTrip() {
    SkeletonGrammar.Result r = SkeletonGrammar.read(good(), VOCAB);
    assertEquals(r.skeleton().tokens(), SkeletonGrammar.tokensOf(r.skeleton().key()));
    String a = SkeletonGrammar.formatHash("NTB", r.skeleton().key());
    assertEquals(a, SkeletonGrammar.formatHash("ntb", r.skeleton().key()));
    assertFalse(a.equals(SkeletonGrammar.formatHash("HNB", r.skeleton().key())));
    assertEquals(64, a.length());
  }

  @Test
  @DisplayName("spellings that mean the same token give the same key")
  void canonicalKey() {
    List<String> plain = good();
    List<String> padded = new java.util.ArrayList<>(good());
    padded.set(0, "~01c");
    padded.set(1, "002c");
    assertEquals(
        SkeletonGrammar.read(plain, VOCAB).skeleton().key(),
        SkeletonGrammar.read(padded, VOCAB).skeleton().key());
    assertEquals(
        "1u|NL|2u|~AMT:c2|9u|AMT:c2|3l",
        SkeletonGrammar.read(List.of("1u", "~NL", "~2u", "~AMT:c2", "9u", "AMT:c2", "3l"), VOCAB).skeleton().key());
  }

  @Test
  @DisplayName("date shapes cannot pad with separators")
  void dateShapesAreTight() {
    assertNull(SkeletonGrammar.parse("DATE:dd/MM/yy "), "trailing space");
    assertNull(SkeletonGrammar.parse("DATE: dd/MM/yy"), "leading space");
    assertNull(SkeletonGrammar.parse("DATE:dd///MM/yy"), "long separator");
  }

  @Test
  @DisplayName("date, time and amount formats render as their shape says")
  void formats() {
    assertEquals("05 March 2026", SkeletonGrammar.date("dd Mmmm yyyy"));
    assertEquals("Mar 5, 2026", SkeletonGrammar.date("Mmm d, yyyy"));
    assertEquals("05MAR26", SkeletonGrammar.date("ddMMMyy"));
    assertEquals("2026-03-05", SkeletonGrammar.date("yyyy-MM-dd"));
    assertEquals("02:35:20 PM", SkeletonGrammar.time("hh:mm:ss a"));
    assertEquals("14:35", SkeletonGrammar.time("HH:mm"));
    assertEquals("1,234.56", SkeletonGrammar.amount("c2"));
    assertEquals("1234", SkeletonGrammar.amount("p0"));
    assertEquals("1234XXXX5678", SkeletonGrammar.account("NNNNXXXXNNNN"));
  }

  @Test
  @DisplayName("line breaks and glued tokens render without stray spaces")
  void spacing() {
    SkeletonGrammar.Result r =
        SkeletonGrammar.read(
            List.of("1u", "2u", "~:", "NL", "9u", "AMT:c2", "~.", "NL", "3c", "~(", "~4c", "~)"), VOCAB);
    assertTrue(r.ok());
    assertEquals("DEAR CUSTOMER:\nLKR {AMT}.\nBill(Payment)", r.skeleton().display());
  }
}
