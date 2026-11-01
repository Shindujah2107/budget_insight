package com.moneyreserve.config;

import com.moneyreserve.domain.BiAppUser;
import com.moneyreserve.dto.CreateTransactionRequest;
import com.moneyreserve.repository.BiAppUserRepository;
import com.moneyreserve.service.BillService;
import com.moneyreserve.service.InsightsTransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local only: a separate demo customer whose last three months of payments show the bills radar
 * working (Dialog, CEB with a jump in the latest bill, water, Netflix and a gym no biller knows),
 * plus salary and everyday spending that is not a bill.
 *
 * <p>Off unless {@code moneyreserve.demo.seed-bills=true}, and never on a profile other than local.
 * It never touches another account: everything is keyed on the demo phone number.
 * Re-running is safe: each payment has a fixed client reference per month, so it is stored once;
 * starting it again in a later month only adds that month's payments.
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "moneyreserve.demo", name = "seed-bills", havingValue = "true")
@RequiredArgsConstructor
public class DemoBillsSeeder implements ApplicationRunner {

  /** Not a real number: 077 000 0099 is in the block the local setup uses for test accounts. */
  public static final String DEMO_PHONE = "+94770000099";

  private final BiAppUserRepository users;
  private final InsightsTransactionService transactions;
  private final BillService bills;
  private final InsightsProperties properties;

  private record Monthly(String key, String merchant, int day, List<String> amounts) {}

  /** Amounts oldest first, one per month; the latest CEB bill is about 35% above the others. */
  private static final List<Monthly> PAYMENTS =
      List.of(
          new Monthly("dialog", "DIALOG AXIATA BILL PAYMENT", 5, List.of("1490.00", "1490.00", "1490.00")),
          new Monthly("ceb", "CEB ELECTRICITY BILL", 22, List.of("4150.00", "4230.00", "5650.00")),
          new Monthly("water", "NWSDB WATER BILL", 18, List.of("1080.00", "1120.00", "1100.00")),
          new Monthly("netflix", "NETFLIX.COM", 17, List.of("1250.00", "1250.00", "1250.00")),
          new Monthly("gym", "SUNRISE FITNESS COLOMBO", 2, List.of("3000.00", "3000.00", "3000.00")),
          new Monthly("salary", "SALARY ACME LANKA PVT LTD", 25, List.of("185000.00", "185000.00", "185000.00")));

  private record Spend(String key, String merchant, int monthsBack, int day, String amount) {}

  /** Everyday spending at irregular days and amounts: it must not look like a bill. */
  private static final List<Spend> SPENDS =
      List.of(
          new Spend("keells", "KEELLS SUPER NUGEGODA", 3, 3, "8740.00"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 3, 12, "2315.50"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 3, 24, "5480.00"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 2, 7, "6215.50"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 2, 28, "3890.00"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 1, 2, "9380.00"),
          new Spend("keells", "KEELLS SUPER NUGEGODA", 1, 19, "1760.00"),
          new Spend("pickme", "PICKME FOOD", 3, 8, "2350.00"),
          new Spend("pickme", "PICKME FOOD", 3, 27, "1450.00"),
          new Spend("pickme", "PICKME FOOD", 2, 15, "1890.00"),
          new Spend("pickme", "PICKME FOOD", 1, 6, "2760.00"),
          new Spend("pickme", "PICKME FOOD", 1, 9, "980.00"),
          new Spend("pickme", "PICKME FOOD", 1, 23, "2140.00"),
          new Spend("fuel", "CEYPETCO FUEL STATION", 3, 11, "6000.00"),
          new Spend("fuel", "CEYPETCO FUEL STATION", 2, 4, "5500.00"),
          new Spend("fuel", "CEYPETCO FUEL STATION", 2, 21, "4000.00"),
          new Spend("fuel", "CEYPETCO FUEL STATION", 1, 14, "6500.00"));

  @Override
  public void run(ApplicationArguments args) {
    if (!properties.isEnabled()) {
      return;
    }
    LocalDate today = LocalDate.now(properties.zoneId());
    BiAppUser user =
        users
            .findByPhone(DEMO_PHONE)
            .orElseGet(
                () ->
                    users.save(
                        BiAppUser.builder()
                            .phone(DEMO_PHONE)
                            .fullName("Demo (bills radar)")
                            .verifiedAt(OffsetDateTime.now())
                            .build()));
    Caller caller = new Caller(user.getId());
    YearMonth now = YearMonth.from(today);
    int stored = 0;
    for (Monthly m : PAYMENTS) {
      for (int i = 0; i < m.amounts().size(); i++) {
        // The three most recent months that already reached this day of the month.
        int back = m.amounts().size() - i - (m.day() <= today.getDayOfMonth() ? 1 : 0);
        YearMonth month = now.minusMonths(back);
        LocalDate date = month.atDay(Math.min(m.day(), month.lengthOfMonth()));
        boolean credit = m.key().equals("salary");
        transactions.create(
            caller,
            CreateTransactionRequest.builder()
                .amount(new BigDecimal(m.amounts().get(i)))
                .currency("LKR")
                .direction(credit ? "CREDIT" : "DEBIT")
                .merchant(m.merchant())
                .txnDate(date)
                .clientRef("demo-bills:" + m.key() + ":" + month)
                .build(),
            now.atDay(1),
            now.atEndOfMonth());
        stored++;
      }
    }
    for (Spend sp : SPENDS) {
      YearMonth month = now.minusMonths(sp.monthsBack());
      LocalDate date = month.atDay(Math.min(sp.day(), month.lengthOfMonth()));
      transactions.create(
          caller,
          CreateTransactionRequest.builder()
              .amount(new BigDecimal(sp.amount()))
              .currency("LKR")
              .direction("DEBIT")
              .merchant(sp.merchant())
              .txnDate(date)
              .clientRef("demo-bills:" + sp.key() + ":" + date)
              .build(),
          now.atDay(1),
          now.atEndOfMonth());
      stored++;
    }
    bills.refresh(user.getId(), today);
    log.info("Demo bills account ready: phone={} payments={} (stored once each)", DEMO_PHONE, stored);
  }
}
