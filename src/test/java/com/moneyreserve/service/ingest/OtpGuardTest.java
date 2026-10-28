package com.moneyreserve.service.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The release-gate test for the server-side OTP and PIN re-check (BI-32, in part).
 *
 * <p>Two halves, and the second is as important as the first. A guard that catches every code is
 * worthless if it also rejects "Coffee Bean" and "Keells Super 4821", because a guard that refuses
 * real spending is a guard somebody switches off — and the customer whose grocery run vanished is
 * the one who reports it.
 *
 * <p>The strings below are shaped like the message bodies a device would be leaking if both of the
 * other two controls had failed. None of them is a real message from a real bank: the labelled
 * sample set is dependency D-1 and has not arrived, so the per-bank half of the release gate cannot
 * be evidenced yet. What is evidenced here is the rule, which is the half that is code.
 */
class OtpGuardTest {

  @Nested
  @DisplayName("Trips on anything that looks like a code or the message that carried one")
  class Trips {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "Your OTP for online banking is 839201.",
          "OTP: 483920",
          "O.T.P is 1234",
          "Your one-time password is 774812",
          "Your one time pass code is 774812",
          "Use verification code 220913 to continue",
          "Security code: 5521",
          "Your authentication code is 88213",
          "Do not share your PIN with anyone",
          "Your new pin is 4417",
          "mPIN reset successful",
          "CVV 123 will not be asked by our staff",
          "Enter passcode 9981 to authorise"
        })
    void keywordBearingMessages(String value) {
      assertTrue(OtpGuard.trips(value), value);
    }

    @Test
    @DisplayName("A sentence carrying a code trips even with no keyword at all")
    void codeInASentenceWithNoKeyword() {
      // The backstop rule. Nothing here says OTP, PIN or code.
      assertTrue(OtpGuard.trips("Use 839201 to log in to your account"));
    }

    @Test
    @DisplayName("Case and spacing do not help it through")
    void caseAndSpacingIrrelevant() {
      assertTrue(OtpGuard.trips("YOUR o t p IS 1234"));
      assertTrue(OtpGuard.trips("your  O.T.P."));
    }

    @Test
    @DisplayName("One bad field among good ones trips the whole message")
    void anyFieldTrips() {
      assertTrue(OtpGuard.trips("Coffee Bean", "COMB", "CARD_PURCHASE", "Your OTP is 1234"));
    }
  }

  @Nested
  @DisplayName("Leaves real merchants and real field values alone")
  class DoesNotTrip {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "Coffee Bean",
          "Keells Super",
          "Keells Super 4821",
          "Shopping Mall",
          "Spinney's",
          "Pizza Pinto",
          "Uber",
          "7-Eleven 1234",
          "Odel Colombo 03",
          "CARD_PURCHASE",
          "COMB",
          "LKR",
          "DEBIT",
          "4821",
          "sha256:9f2b4c1d8e7a0b6f3c5d2e1a4b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8"
        })
    void ordinaryValues(String value) {
      assertFalse(OtpGuard.trips(value), value);
    }

    @Test
    @DisplayName("\"Shopping\" contains the letters of PIN and must still pass")
    void wordBoundaryHoldsForPin() {
      // The single reason "pin" is matched on a word boundary rather than as a substring.
      assertFalse(OtpGuard.trips("Shopping"));
      assertFalse(OtpGuard.trips("Spinner Cafe"));
    }

    @Test
    @DisplayName("A short merchant with a store number is not a sentence with a code")
    void storeNumbersSurvive() {
      // Two words. The sentence rule needs four before a digit run means anything.
      assertFalse(OtpGuard.trips("Cargills 4821"));
    }

    @Test
    @DisplayName("Null and blank are not detections")
    void nullAndBlank() {
      assertFalse(OtpGuard.trips((String) null));
      assertFalse(OtpGuard.trips(""));
      assertFalse(OtpGuard.trips("   "));
      assertFalse(OtpGuard.trips((String[]) null));
      assertFalse(OtpGuard.trips(null, null));
    }
  }
}
