package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.dto.AskMrSettings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ask MR rules: periods, thresholds, the usual payment")
class AskMrRulesTest {

  private static final AskMrSettings S = AskMrSettings.DEFAULTS;

  @Test
  @DisplayName("month: completed days against the same dates last month, short months capped; none on the 1st")
  void month() {
    AskMrServiceImpl.Window w = AskMrServiceImpl.monthWindow(LocalDate.of(2026, 11, 21));
    assertEquals(LocalDate.of(2026, 11, 1), w.from());
    assertEquals(LocalDate.of(2026, 11, 20), w.to());
    assertEquals(LocalDate.of(2026, 10, 1), w.prevFrom());
    assertEquals(LocalDate.of(2026, 10, 20), w.prevTo());
    AskMrServiceImpl.Window march = AskMrServiceImpl.monthWindow(LocalDate.of(2027, 3, 31));
    assertEquals(LocalDate.of(2027, 3, 28), march.to(), "February 2027 has 28 days");
    assertEquals(LocalDate.of(2027, 2, 28), march.prevTo());
    assertNull(AskMrServiceImpl.monthWindow(LocalDate.of(2026, 11, 1)));
  }

  @Test
  @DisplayName("week: Monday to today against the same days the week before; an earlier week is whole; a future date is today")
  void week() {
    LocalDate thursday = LocalDate.of(2026, 11, 19);
    AskMrServiceImpl.Window w = AskMrServiceImpl.weekWindow(thursday, null);
    assertEquals(LocalDate.of(2026, 11, 16), w.from());
    assertEquals(thursday, w.to());
    assertEquals(LocalDate.of(2026, 11, 9), w.prevFrom());
    assertEquals(LocalDate.of(2026, 11, 12), w.prevTo());
    AskMrServiceImpl.Window last = AskMrServiceImpl.weekWindow(thursday, LocalDate.of(2026, 11, 11));
    assertEquals(LocalDate.of(2026, 11, 9), last.from());
    assertEquals(LocalDate.of(2026, 11, 15), last.to());
    AskMrServiceImpl.Window sunday = AskMrServiceImpl.weekWindow(LocalDate.of(2026, 11, 22), null);
    assertEquals(LocalDate.of(2026, 11, 16), sunday.from());
    assertEquals(LocalDate.of(2026, 11, 22), sunday.to());
    assertEquals(w, AskMrServiceImpl.weekWindow(thursday, LocalDate.of(2027, 1, 1)));
  }

  @Test
  @DisplayName("meaningful: at least LKR 1,000 and 15%, both ways; anything from zero that clears the amount")
  void meaningful() {
    assertTrue(AskMrServiceImpl.meaningful(bd("3200"), bd("9200"), S));
    assertTrue(AskMrServiceImpl.meaningful(bd("-2100"), bd("8400"), S));
    assertFalse(AskMrServiceImpl.meaningful(bd("999.99"), bd("1000"), S), "under the amount");
    assertFalse(AskMrServiceImpl.meaningful(bd("10000"), bd("100000"), S), "10% is under 15%");
    assertTrue(AskMrServiceImpl.meaningful(bd("15000"), bd("100000"), S), "15% exactly");
    assertTrue(AskMrServiceImpl.meaningful(bd("1500"), bd("0"), S), "new spending");
    assertFalse(AskMrServiceImpl.meaningful(bd("0"), bd("0"), S));
  }

  @Test
  @DisplayName("the usual payment: the median of at least 3")
  void median() {
    assertNull(AskMrServiceImpl.median(List.of(t("100"), t("200"))));
    assertEquals(bd("200"), AskMrServiceImpl.median(List.of(t("300"), t("100"), t("200"))));
    assertEquals(new BigDecimal("250.00"), AskMrServiceImpl.median(List.of(t("100"), t("200"), t("300"), t("900"))));
  }

  @Test
  @DisplayName("merchant names: real names are clear; empty, POS, terminal and bank codes are not")
  void clearNames() {
    for (String clear :
        new String[] {"THANJAI VIRUNTHU COLOMBO 03 LK", "PickMe Food", "Keells Super", "Shell", "KFC", "Uber *Trip", "Cafe Kumbuk", "කීල්ස්"}) {
      assertTrue(AskMrServiceImpl.clearName(clear), clear);
    }
    for (String unclear :
        new String[] {
          "", "   ", "POS", "pos", "POS 4411", "POS PURCHASE", "POS-001234 LK", "ATM WDL", "CARD PAYMENT", "IPG-77812",
          "TID 00420017", "Online Transfer", "N/A", "12345678", "AB 1234", "-"
        }) {
      assertFalse(AskMrServiceImpl.clearName(unclear), unclear);
    }
    assertFalse(AskMrServiceImpl.clearName(null));
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static BiTransaction t(String amount) {
    return BiTransaction.builder().amount(bd(amount)).build();
  }
}
