package com.moipay.budgetinsights.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MerchantText (categorisation layer 1)")
class MerchantTextTest {

  @Test
  @DisplayName("case, spacing and punctuation")
  void punctuation() {
    assertEquals("WATER BOARD BILL", MerchantText.clean("water-board  bill"));
    assertEquals("FILLING STATION KANDY", MerchantText.clean("FILLING-STATION, KANDY"));
    assertEquals("APPLE COM BILL SINGAPORE", MerchantText.clean("APPLE.COM/BILL SINGAPORE SG"));
    assertEquals("MB SADAH", MerchantText.clean("MB:Sadah"));
  }

  @Test
  @DisplayName("& becomes AND; apostrophes are removed, not split")
  void ampersandAndApostrophes() {
    assertEquals("PERERA AND SONS", MerchantText.clean("Perera & Sons"));
    assertEquals("MCDONALDS COLOMBO", MerchantText.clean("McDonald's Colombo"));
    assertEquals("KURUNMA CRM3 BR", MerchantText.clean("KURUN'MA-CRM3 BR"));
  }

  @Test
  @DisplayName("processor prefix, long numbers and a trailing country code are dropped")
  void noise() {
    assertEquals("DOMINOS PIZZA SRI", MerchantText.clean("PH *DOMINOS PIZZA SRI, LK"));
    assertEquals("HIGGSFIELD INC", MerchantText.clean("HIGGSFIELD INC. +14088370029 US"));
    // A prefix is a short processor code (<= 8 characters); a longer "NAME*" is the merchant itself.
    assertEquals("SURFSHARK SURFSHARK", MerchantText.clean("SURFSHARK* SURFSHARK., US"));
    assertEquals("UBER TRIP", MerchantText.clean("SQ *UBER TRIP, US"));
    // Any other NAME* is the brand and is kept.
    assertEquals("UBER TRIP", MerchantText.clean("UBER *TRIP"));
    assertEquals("KEELLS SUPER", MerchantText.clean("KEELLS*SUPER"));
    assertEquals("SPOTIFY P1234", MerchantText.clean("SPOTIFY *P1234"));
    assertEquals("STAR TAC CELLULAR COLOMBO 11", MerchantText.clean("Star Tac Cellular COLOMBO-11 LK"));
    assertEquals("KEELLS SUPER COLOMBO 04", MerchantText.clean("KEELLS SUPER COLOMBO 04"));
  }

  @Test
  @DisplayName("a lone country-code-like word is kept; nothing left is null")
  void edges() {
    assertEquals(List.of("LK"), MerchantText.tokens("LK"));
    assertNull(MerchantText.clean("  *** "));
    assertNull(MerchantText.clean(null));
  }

  @Test
  @DisplayName("keywords keep every word typed")
  void words() {
    assertEquals(List.of("DARAZ", "LK"), MerchantText.words("daraz.lk"));
  }
}
