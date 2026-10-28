package com.moneyreserve.service;

import com.moneyreserve.dto.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Fixture responses for contract-first stub mode (BI-38).
 *
 * <p>These exist so the frontend can build the whole Insights screen against the published contract
 * in week one, before any of the logic behind it exists. They are built from the real response
 * types rather than hand-written JSON, so a change to a DTO breaks the fixtures at compile time
 * instead of quietly letting the stub drift away from the contract the frontend is integrating
 * against.
 *
 * <p><b>The numbers here are internally consistent, unlike the placeholders in the design.</b> The
 * design shows a total that does not reconcile to its category rows and shares that sum to 194%.
 * Reproducing that would teach the frontend to tolerate figures that the real backend will never
 * send. Here the category amounts sum exactly to the stated total and the shares sum to exactly
 * 100, which is what the live service guarantees.
 *
 * <p>Dates are derived from today rather than pinned, so the screen looks alive and the
 * days-elapsed text exercises real arithmetic across month boundaries.
 */
@Component
public class InsightsStubFixtures {

  private static final String LKR = "LKR";

  // The seeded category ids from V2.19.1, so stub responses reference real categories.
  private static final UUID CAT_TRANSPORT = id(1);
  private static final UUID CAT_HEALTH_CARE = id(3);
  private static final UUID CAT_DINING_OUT = id(5);
  private static final UUID CAT_ENTERTAINMENT = id(6);
  private static final UUID CAT_PERSONAL_CARE = id(7);
  private static final UUID CAT_SHOPPING = id(8);
  private static final UUID CAT_SUBSCRIPTIONS = id(14);
  private static final UUID CAT_BILLS_UTILITIES = id(16);

  private static final UUID ACCOUNT_CARD = UUID.fromString("b1a00000-0000-4000-a000-000000000001");
  private static final UUID ACCOUNT_BANK = UUID.fromString("b1a00000-0000-4000-a000-000000000002");

  private static UUID id(int n) {
    return UUID.fromString(String.format("b1c00000-0000-4000-a000-%012d", n));
  }

  private static String money(String v) {
    return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** Monthly budget card. Target and spend are fixed; the day arithmetic is real. */
  public BudgetSummaryResponse summary(YearMonth month) {
    YearMonth m = month != null ? month : YearMonth.now();
    LocalDate start = m.atDay(1);
    LocalDate end = m.atEndOfMonth();
    LocalDate today = LocalDate.now();

    // Clamp so a request for a past or future month still returns sane day counts.
    LocalDate reference = today.isBefore(start) ? start : (today.isAfter(end) ? end : today);
    int daysInPeriod = m.lengthOfMonth();
    int daysElapsed = reference.getDayOfMonth();
    int daysRemaining = daysInPeriod - daysElapsed;

    BigDecimal target = new BigDecimal("30000.00");
    BigDecimal spent = new BigDecimal("15500.00");
    BigDecimal remaining = target.subtract(spent);
    double utilisation =
        spent
            .multiply(BigDecimal.valueOf(100))
            .divide(target, 1, RoundingMode.HALF_UP)
            .doubleValue();

    BigDecimal avgDaily =
        spent.divide(BigDecimal.valueOf(Math.max(daysElapsed, 1)), 2, RoundingMode.HALF_UP);
    BigDecimal idealDaily =
        target.divide(BigDecimal.valueOf(daysInPeriod), 2, RoundingMode.HALF_UP);

    String variance =
        avgDaily.compareTo(idealDaily) > 0
            ? "ABOVE"
            : (avgDaily.compareTo(idealDaily) < 0 ? "UNDER" : "ON");
    String status =
        utilisation > 100
            ? "OVER_BUDGET"
            : ("ABOVE".equals(variance) ? "AHEAD_OF_PACE" : "ON_TRACK");

    return BudgetSummaryResponse.builder()
        .period(PeriodDto.builder().start(start).end(end).daysInPeriod(daysInPeriod).build())
        .budgetSet(true)
        .currency(LKR)
        .monthlyTarget(money(target.toPlainString()))
        .spentToDate(money(spent.toPlainString()))
        .remaining(money(remaining.toPlainString()))
        .utilisationPct(utilisation)
        .status(status)
        .daysElapsed(daysElapsed)
        .daysRemaining(daysRemaining)
        .daysText(
            daysElapsed
                + (daysElapsed == 1 ? " day" : " days")
                + " into your budget · "
                + daysRemaining
                + (daysRemaining == 1 ? " day left" : " days left"))
        .pace(
            BudgetSummaryResponse.Pace.builder()
                .averageDailySpend(money(avgDaily.toPlainString()))
                .idealDailySpend(money(idealDaily.toPlainString()))
                .variance(variance)
                .build())
        .categoryAlerts(
            List.of(
                BudgetSummaryResponse.CategoryAlert.builder()
                    .categoryCode("DINING_OUT")
                    .limit(money("7000"))
                    .spent(money("6250"))
                    .state("APPROACHING")
                    .build()))
        .build();
  }

  /**
   * The no-target variant, so the frontend can build the set-a-budget prompt without waiting for a
   * way to clear a budget.
   */
  public BudgetSummaryResponse summaryWithoutBudget(YearMonth month) {
    YearMonth m = month != null ? month : YearMonth.now();
    return BudgetSummaryResponse.builder()
        .period(
            PeriodDto.builder()
                .start(m.atDay(1))
                .end(m.atEndOfMonth())
                .daysInPeriod(m.lengthOfMonth())
                .build())
        .budgetSet(false)
        .currency(LKR)
        .suggestion(
            BudgetSummaryResponse.Suggestion.builder()
                .suggestedTarget(money("28000"))
                .basis("Median of your last three months")
                .build())
        .build();
  }

  /** Payment activity for a week. Seven points per series, zeros included. */
  public PaymentActivityResponse activity(LocalDate weekStart, UUID accountId) {
    LocalDate start = weekStart != null ? weekStart : LocalDate.now().minusDays(6);
    String[] card = {"1100.00", "2150.00", "0.00", "3050.00", "1420.00", "2600.00", "900.00"};
    String[] bank = {"1180.00", "2210.00", "640.00", "2980.00", "1700.00", "3900.00", "450.00"};

    boolean cardOnly = ACCOUNT_CARD.equals(accountId);
    boolean bankOnly = ACCOUNT_BANK.equals(accountId);
    String[] zeros = {"0.00", "0.00", "0.00", "0.00", "0.00", "0.00", "0.00"};

    // Both series, always -- matching what the live path returns since R5. The fixture is what the
    // frontend builds the chart against, so a fixture that drops a series under a filter teaches
    // the client a shape the real endpoint never produces, which is the drift stub mode exists to
    // prevent.
    List<PaymentActivityResponse.Series> series = new ArrayList<>();
    series.add(seriesOf("CARD", "Card payments", start, bankOnly ? zeros : card));
    series.add(seriesOf("BANK", "Bank account payments", start, cardOnly ? zeros : bank));

    BigDecimal max = BigDecimal.ZERO;
    for (PaymentActivityResponse.Series s : series) {
      for (PaymentActivityResponse.Point p : s.getPoints()) {
        max = max.max(new BigDecimal(p.getAmount()));
      }
    }

    return PaymentActivityResponse.builder()
        .range(PeriodDto.builder().start(start).end(start.plusDays(6)).daysInPeriod(7).build())
        .currency(LKR)
        .maxValue(money(max.toPlainString()))
        .series(series)
        .build();
  }

  private PaymentActivityResponse.Series seriesOf(
      String key, String label, LocalDate start, String[] amounts) {
    List<PaymentActivityResponse.Point> points = new ArrayList<>();
    for (int i = 0; i < amounts.length; i++) {
      points.add(
          PaymentActivityResponse.Point.builder()
              .date(start.plusDays(i))
              .amount(money(amounts[i]))
              .build());
    }
    return PaymentActivityResponse.Series.builder().key(key).label(label).points(points).build();
  }

  /** Spending breakdown. Amounts sum to the total; shares sum to exactly 100. */
  public SpendingBreakdownResponse breakdown(LocalDate from, LocalDate to) {
    LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
    LocalDate end = to != null ? to : LocalDate.now();

    List<SpendingBreakdownResponse.CategorySpend> categories =
        List.of(
            spend(CAT_DINING_OUT, "DINING_OUT", "Dining Out", "ic_dining_out", "6250.00", 25.0, 5),
            spend(CAT_TRANSPORT, "TRANSPORT", "Transport", "ic_transport", "4500.00", 18.0, 9),
            spend(
                CAT_BILLS_UTILITIES,
                "BILLS_UTILITIES",
                "Bills & Utilities",
                "ic_bills_utilities",
                "3750.00",
                15.0,
                3),
            spend(CAT_SHOPPING, "SHOPPING", "Shopping", "ic_shopping", "3250.00", 13.0, 4),
            spend(
                CAT_ENTERTAINMENT,
                "ENTERTAINMENT",
                "Entertainment",
                "ic_entertainment",
                "2750.00",
                11.0,
                2),
            spend(
                CAT_HEALTH_CARE, "HEALTH_CARE", "Health Care", "ic_health_care", "2000.00", 8.0, 2),
            spend(
                CAT_SUBSCRIPTIONS,
                "SUBSCRIPTIONS",
                "Subscriptions",
                "ic_subscriptions",
                "1500.00",
                6.0,
                3),
            spend(
                CAT_PERSONAL_CARE,
                "PERSONAL_CARE",
                "Personal Care",
                "ic_personal_care",
                "1000.00",
                4.0,
                1));

    return SpendingBreakdownResponse.builder()
        .range(PeriodDto.builder().start(start).end(end).build())
        .currency(LKR)
        .total(money("25000"))
        .categoryCount(17)
        .returnedCount(categories.size())
        .categories(categories)
        .build();
  }

  private SpendingBreakdownResponse.CategorySpend spend(
      UUID id, String code, String name, String icon, String amount, double share, int count) {
    return SpendingBreakdownResponse.CategorySpend.builder()
        .categoryId(id)
        .code(code)
        .name(name)
        .iconKey(icon)
        .amount(money(amount))
        .sharePct(share)
        .transactionCount(count)
        .hasMore(count > 0)
        .build();
  }

  /** Transactions behind one category. They sum to that category's breakdown amount. */
  public CategoryTransactionsResponse categoryTransactions(UUID categoryId, int page, int size) {
    List<TransactionDto> all =
        List.of(
            txn("Burger Hub", 11, "2800.00", ACCOUNT_CARD, "Commercial •4821"),
            txn("Dinner at Ministry", 9, "1850.00", ACCOUNT_BANK, "Sampath •1207"),
            txn("Cafe Kumbuk", 6, "620.00", ACCOUNT_CARD, "Commercial •4821"),
            txn("Lunch at Nuga", 4, "530.00", ACCOUNT_CARD, "Commercial •4821"),
            txn("Coffee Shop", 3, "450.00", ACCOUNT_CARD, "Commercial •4821"));

    int fromIndex = Math.min(page * size, all.size());
    int toIndex = Math.min(fromIndex + size, all.size());

    return CategoryTransactionsResponse.builder()
        .categoryId(categoryId != null ? categoryId : CAT_DINING_OUT)
        .code("DINING_OUT")
        .name("Dining Out")
        .total(money("6250"))
        .page(
            CategoryTransactionsResponse.PageDto.builder()
                .number(page)
                .size(size)
                .totalElements((long) all.size())
                .totalPages((int) Math.ceil((double) all.size() / Math.max(size, 1)))
                .build())
        .transactions(all.subList(fromIndex, toIndex))
        .build();
  }

  private TransactionDto txn(
      String merchant, int daysAgo, String amount, UUID accountId, String label) {
    return TransactionDto.builder()
        .id(UUID.randomUUID())
        .merchant(merchant)
        .date(LocalDate.now().minusDays(daysAgo))
        .amount(money(amount))
        .direction("DEBIT")
        .displayAmount("-" + LKR + " " + money(amount))
        .categoryId(CAT_DINING_OUT)
        .accountId(accountId)
        .accountLabel(label)
        .source("SMS_ANDROID")
        .editable(true)
        .isUserCategorised(false)
        .build();
  }

  /** Accounts for the All Accounts filter. Masked tails only. */
  public List<AccountDto> accounts() {
    return List.of(
        AccountDto.builder()
            .accountId(ACCOUNT_CARD)
            .bankCode("COMB")
            .instrumentType("CARD")
            .maskedTail("4821")
            .label("Commercial •4821")
            .build(),
        AccountDto.builder()
            .accountId(ACCOUNT_BANK)
            .bankCode("SAMP")
            .instrumentType("BANK")
            .maskedTail("1207")
            .label("Sampath •1207")
            .build());
  }

  /** The full category list, in display order. */
  public List<CategoryDto> categories() {
    String[][] rows = {
      {"1", "TRANSPORT", "Transport", "ic_transport"},
      {"2", "UTILITIES", "Utilities", "ic_utilities"},
      {"3", "HEALTH_CARE", "Health Care", "ic_health_care"},
      {"4", "DEBT_PAYMENTS", "Debt Payments", "ic_debt_payments"},
      {"5", "DINING_OUT", "Dining Out", "ic_dining_out"},
      {"6", "ENTERTAINMENT", "Entertainment", "ic_entertainment"},
      {"7", "PERSONAL_CARE", "Personal Care", "ic_personal_care"},
      {"8", "SHOPPING", "Shopping", "ic_shopping"},
      {"9", "GIFTS_DONATION", "Gifts / Donation", "ic_gifts_donation"},
      {"10", "EDUCATION", "Education", "ic_education"},
      {"11", "WORK", "Work", "ic_work"},
      {"12", "TRAVEL", "Travel", "ic_travel"},
      {"13", "GYM", "Gym", "ic_gym"},
      {"14", "SUBSCRIPTIONS", "Subscriptions", "ic_subscriptions"},
      {"15", "TRANSFERS", "Transfers", "ic_transfers"},
      {"16", "BILLS_UTILITIES", "Bills & Utilities", "ic_bills_utilities"},
      {"17", "OTHER", "Other", "ic_other"}
    };
    List<CategoryDto> out = new ArrayList<>(rows.length);
    for (String[] r : rows) {
      int order = Integer.parseInt(r[0]);
      out.add(
          CategoryDto.builder()
              .categoryId(id(order))
              .code(r[1])
              .name(r[2])
              .iconKey(r[3])
              .sortOrder(order)
              .build());
    }
    return out;
  }
}
