package com.moneyreserve.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillType;
import com.moneyreserve.service.calc.BillDetector.Found;
import com.moneyreserve.service.calc.BillDetector.KnownBiller;
import com.moneyreserve.service.calc.BillDetector.Payment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Bill detection")
class BillDetectorTest {

  private final BillDetector detector = new BillDetector();
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

  private static final KnownBiller CEB = biller("CEB", "CEB electricity", BillType.ELECTRICITY, "CEB|CEYLON ELECTRICITY");
  private static final KnownBiller NETFLIX = biller("NETFLIX", "Netflix", BillType.STREAMING, "NETFLIX");
  private static final List<KnownBiller> BILLERS = List.of(CEB, NETFLIX);

  private static KnownBiller biller(String code, String name, BillType type, String terms) {
    return new KnownBiller(
        UUID.randomUUID(), code, name, type, type.defaultTolerancePct(), MerchantTerms.parse(terms));
  }

  private static Payment paid(String date, String amount, String merchant) {
    return new Payment(LocalDate.parse(date), new BigDecimal(amount), false, merchant, false);
  }

  private static Payment refund(String date, String amount, String merchant) {
    return new Payment(LocalDate.parse(date), new BigDecimal(amount), true, merchant, false);
  }

  private List<Found> detect(Payment... payments) {
    return detector.detect(List.of(payments), BILLERS, TODAY);
  }

  private static Found only(List<Found> found) {
    assertEquals(1, found.size(), found.toString());
    return found.get(0);
  }

  @Test
  @DisplayName("a monthly subscription: cadence, usual amount and next date on its usual day")
  void monthlySubscription() {
    Found f =
        only(
            detect(
                paid("2026-08-14", "2900.00", "NETFLIX.COM"),
                paid("2026-09-14", "2900.00", "NETFLIX.COM"),
                paid("2026-10-14", "2900.00", "Netflix.com")));
    assertEquals("B:NETFLIX", f.payeeKey());
    assertEquals("Netflix", f.name());
    assertEquals(BillCadence.MONTHLY, f.cadence());
    assertEquals(new BigDecimal("2900.00"), f.expectedAmount());
    assertEquals(LocalDate.of(2026, 11, 14), f.nextDueOn());
    assertEquals(14, f.anchorDay());
    assertEquals(3, f.paymentCount());
  }

  @Test
  @DisplayName("a known biller counts after two payments; any other payee needs three")
  void minimumPayments() {
    assertEquals(
        1, detect(paid("2026-09-03", "4100", "CEB BILL PAY"), paid("2026-10-02", "4650", "CEB BILL PAY")).size());
    assertTrue(detect(paid("2026-09-03", "1500", "SUNRISE GYM"), paid("2026-10-03", "1500", "SUNRISE GYM")).isEmpty());
    Found gym =
        only(
            detect(
                paid("2026-08-03", "1500", "SUNRISE GYM"),
                paid("2026-09-03", "1500", "Sunrise  Gym"),
                paid("2026-10-03", "1500", "SUNRISE GYM")));
    assertEquals("M:SUNRISE GYM", gym.payeeKey());
    assertNull(gym.biller());
  }

  @Test
  @DisplayName("irregular amounts from an unknown payee are not a bill; a utility may vary")
  void irregularAmounts() {
    assertTrue(
        detect(
                paid("2026-08-05", "800", "KEELLS SUPER"),
                paid("2026-09-05", "4200", "KEELLS SUPER"),
                paid("2026-10-05", "1900", "KEELLS SUPER"))
            .isEmpty());
    Found ceb =
        only(
            detect(
                paid("2026-08-02", "3800", "CEB"),
                paid("2026-09-01", "4600", "CEB"),
                paid("2026-10-02", "4100", "CEB")));
    assertEquals(new BigDecimal("4100.00"), ceb.expectedAmount());
  }

  @Test
  @DisplayName("a bill that jumps is still found, with the average it jumped from")
  void jumpStillFound() {
    Found ceb =
        only(
            detect(
                paid("2026-07-02", "4000", "CEB"),
                paid("2026-08-02", "4200", "CEB"),
                paid("2026-09-01", "4100", "CEB"),
                paid("2026-10-02", "7800", "CEB")));
    assertEquals(new BigDecimal("7800"), ceb.lastAmount());
    assertEquals(new BigDecimal("4100.00"), ceb.previousAverage());
  }

  @Test
  @DisplayName("one skipped month is still monthly; two are not")
  void skippedMonths() {
    Found f =
        only(
            detect(
                paid("2026-05-10", "1500", "SUNRISE GYM"),
                paid("2026-06-10", "1500", "SUNRISE GYM"),
                paid("2026-08-10", "1500", "SUNRISE GYM"),
                paid("2026-09-10", "1500", "SUNRISE GYM"),
                paid("2026-10-10", "1500", "SUNRISE GYM")));
    assertEquals(BillCadence.MONTHLY, f.cadence());
    assertTrue(
        detect(
                paid("2026-04-10", "1500", "SUNRISE GYM"),
                paid("2026-06-10", "1500", "SUNRISE GYM"),
                paid("2026-08-10", "1500", "SUNRISE GYM"),
                paid("2026-09-10", "1500", "SUNRISE GYM"))
            .isEmpty());
  }

  @Test
  @DisplayName("month-end bills stay on the month's last day, including February")
  void monthEnd() {
    Found f =
        only(
            detect(
                paid("2026-07-31", "25000", "LANDLORD RENT"),
                paid("2026-08-31", "25000", "LANDLORD RENT"),
                paid("2026-09-30", "25000", "LANDLORD RENT")));
    assertEquals(31, f.anchorDay(), "30 September is that month's last day");
    assertEquals(LocalDate.of(2026, 10, 31), f.nextDueOn());
    assertEquals(LocalDate.of(2027, 2, 28), BillDetector.nextDue(LocalDate.of(2027, 1, 31), BillCadence.MONTHLY, 31));
    assertEquals(LocalDate.of(2026, 12, 1), BillDetector.nextDue(LocalDate.of(2026, 10, 30), BillCadence.MONTHLY, 1));
  }

  @Test
  @DisplayName("a re-sent SMS counts once and a refund cancels its payment")
  void duplicatesAndRefunds() {
    Found f =
        only(
            detect(
                paid("2026-08-14", "2900", "NETFLIX"),
                paid("2026-09-14", "2900", "NETFLIX"),
                paid("2026-09-14", "2900", "NETFLIX"),
                paid("2026-10-14", "2900", "NETFLIX")));
    assertEquals(3, f.paymentCount());
    assertTrue(
        detect(
                paid("2026-08-03", "1500", "SUNRISE GYM"),
                paid("2026-09-03", "1500", "SUNRISE GYM"),
                paid("2026-10-03", "1500", "SUNRISE GYM"),
                refund("2026-10-06", "1500", "SUNRISE GYM"))
            .isEmpty(),
        "with one payment refunded, two remain: not yet a bill");
  }

  @Test
  @DisplayName("a bill more than two periods overdue has stopped")
  void stopped() {
    assertTrue(
        detect(
                paid("2026-03-14", "2900", "NETFLIX"),
                paid("2026-04-14", "2900", "NETFLIX"),
                paid("2026-05-14", "2900", "NETFLIX"))
            .isEmpty());
  }

  @Test
  @DisplayName("bank transfers are not grouped by description; a biller paid by transfer is")
  void transfers() {
    List<Payment> transfers = new ArrayList<>();
    for (String d : List.of("2026-08-01", "2026-09-01", "2026-10-01")) {
      transfers.add(new Payment(LocalDate.parse(d), new BigDecimal("10000"), false, "CEFT TRANSFER", true));
    }
    assertTrue(detector.detect(transfers, BILLERS, TODAY).isEmpty());
    List<Payment> ceb = new ArrayList<>();
    for (String d : List.of("2026-09-02", "2026-10-02")) {
      ceb.add(new Payment(LocalDate.parse(d), new BigDecimal("4000"), false, "CEFT CEB BILL", true));
    }
    assertEquals("B:CEB", only(detector.detect(ceb, BILLERS, TODAY)).payeeKey());
  }

  @Test
  @DisplayName("weekly and yearly cadences")
  void weeklyAndYearly() {
    Found weekly =
        only(
            detect(
                paid("2026-10-04", "500", "PAPER DELIVERY"),
                paid("2026-10-11", "500", "PAPER DELIVERY"),
                paid("2026-10-18", "500", "PAPER DELIVERY"),
                paid("2026-10-25", "500", "PAPER DELIVERY")));
    assertEquals(BillCadence.WEEKLY, weekly.cadence());
    assertEquals(LocalDate.of(2026, 11, 1), weekly.nextDueOn());
    assertNull(weekly.anchorDay());
    Found yearly =
        only(
            detect(
                paid("2024-03-20", "36000", "CEYLINCO GENERAL"),
                paid("2025-03-20", "36000", "CEYLINCO GENERAL"),
                paid("2026-03-20", "36000", "CEYLINCO GENERAL")));
    assertEquals(BillCadence.YEARLY, yearly.cadence());
    assertEquals(LocalDate.of(2027, 3, 20), yearly.nextDueOn());
  }

  @Test
  @DisplayName("dates are the recorded Sri Lanka dates: a payment just after midnight is that day's")
  void localDates() {
    Found f =
        only(
            detect(
                paid("2026-08-01", "2900", "NETFLIX"),
                paid("2026-09-01", "2900", "NETFLIX"),
                paid("2026-10-01", "2900", "NETFLIX")));
    assertEquals(LocalDate.of(2026, 11, 1), f.nextDueOn());
    assertNotNull(f.lastPaidOn());
  }
}
