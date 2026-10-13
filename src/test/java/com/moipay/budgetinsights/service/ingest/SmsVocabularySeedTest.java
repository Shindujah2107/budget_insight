package com.moipay.budgetinsights.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seeded vocabulary is the list of words a handset may name. A word added here is a word that
 * can leave every phone, so the list is checked, not trusted.
 */
@DisplayName("SMS vocabulary seed (V1.13.0)")
class SmsVocabularySeedTest {

  /** Words that belong to OTP / login messages, which are never in scope. */
  private static final Set<String> NEVER =
      Set.of(
          "OTP", "PIN", "MPIN", "PASSWORD", "PASSCODE", "CODE", "CVV", "CVC", "VERIFY",
          "VERIFICATION", "SECURITY", "LOGIN", "LOGON", "SECRET", "AUTHENTICATE", "ACCESS",
          "AUTH", "EXPIRES", "EXPIRY", "SHARE", "DISCLOSE");

  @Test
  @DisplayName("ids are 1..n with no gaps and words are unique upper-case letters")
  void shape() {
    Map<Integer, String> v = SmsVocabularyFixture.byId();
    assertTrue(v.size() >= 200, "vocabulary size " + v.size());
    int expected = 1;
    for (Map.Entry<Integer, String> e : v.entrySet()) {
      assertEquals(expected++, e.getKey());
      assertTrue(e.getValue().matches("^[A-Z]{1,24}$"), e.getValue());
    }
    assertEquals(v.size(), Set.copyOf(v.values()).size(), "duplicate words");
  }

  @Test
  @DisplayName("no word is OTP, PIN or login wording, alone or as the guard sees it")
  void noOtpWording() {
    for (String w : SmsVocabularyFixture.byId().values()) {
      assertFalse(NEVER.contains(w), w);
      assertFalse(OtpGuard.trips(w), w);
    }
  }

  @Test
  @DisplayName("the words of the seeded templates are in the vocabulary")
  void templateWordsPresent() {
    Map<String, Integer> v = SmsVocabularyFixture.byWord();
    for (String w :
        List.of(
            "DEAR", "CARDHOLDER", "PURCHASE", "AUTHORISED", "DEBIT", "CREDIT", "CARD", "ENDING",
            "CRM", "DEPOSIT", "JUSTPAY", "SUCESSFULLY", "DEBITED", "CREDITED", "AC", "NO", "REASON",
            "BAL", "REMARKS", "HNB", "ALERT", "LOCATION", "AMOUNT", "APPROX", "AV", "WITHDRAWAL",
            "RECEIPT", "AMT", "TXN", "FEE", "TERM", "ID", "BALANCE", "AVAILABLE", "SEYLAN", "AVL",
            "POS", "TRX", "LKR", "RS", "LKA", "E", "A", "C")) {
      assertTrue(v.containsKey(w), "missing " + w);
    }
  }
}
