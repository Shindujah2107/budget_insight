package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBudget;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.dto.RecommendationsResponse;
import com.moneyreserve.repository.BiBudgetRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.InsightsMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 50/30/20 recommendations (BI-26).
 *
 * <p>Two claims here are easy to get wrong and expensive to get wrong quietly. The first is that
 * the suggested cap is a median over <i>every</i> sampled month, including the months a category
 * did not appear in at all — without that, one wedding makes a monthly habit. The second is that
 * the savings bucket credits categories mapped to savings rather than subtracting them: a customer
 * clearing a loan must not be charged for it twice, once out of the residual and again by seeing
 * the repayment printed under a bucket whose figure excluded it.
 *
 * <p>Every date here is derived from {@code LocalDate.now(zone)} rather than written down, because
 * the service clamps its trailing window against today and a fixture month would pass in September
 * and fail the following March.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationServiceImplTest {

  @Mock private BiBudgetRepository budgets;
  @Mock private BiTransactionRepository transactions;
  @Mock private BiCategoryRepository categories;

  private InsightsProperties properties;
  private RecommendationServiceImpl service;

  private static final UUID USER = UUID.randomUUID();
  private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

  private static final UUID BILLS = UUID.randomUUID();
  private static final UUID TRANSPORT = UUID.randomUUID();
  private static final UUID DINING = UUID.randomUUID();
  private static final UUID SHOPPING = UUID.randomUUID();
  private static final UUID DEBT = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();
  private static final UUID TRANSFERS = UUID.randomUUID();

  private LocalDate today;
  private YearMonth viewed;
  private YearMonth m1;
  private YearMonth m2;
  private YearMonth m3;

  @BeforeEach
  void setUp() {
    properties = new InsightsProperties();
    properties.setEnabled(true);
    properties.setCurrency("LKR");
    properties.setZone(ZONE.getId());
    properties.setLookbackMonths(6);

    InsightsProperties.Recommendations config = properties.getRecommendations();
    config.setEnabled(true);
    config.setNeedsPct(50);
    config.setWantsPct(30);
    config.setSavingsPct(20);
    config.setTrailingMonths(3);
    config.setNeeds(new ArrayList<>(List.of("BILLS_UTILITIES", "TRANSPORT")));
    config.setWants(new ArrayList<>(List.of("DINING_OUT", "SHOPPING")));
    config.setSavings(new ArrayList<>(List.of("DEBT_PAYMENTS")));

    service =
        new RecommendationServiceImpl(
            properties,
            new InsightsMapper(),
            budgets,
            transactions,
            categories,
            BudgetServiceImplTest.policyFrom(properties));

    today = LocalDate.now(ZONE);
    viewed = YearMonth.from(today);
    m1 = viewed.minusMonths(1);
    m2 = viewed.minusMonths(2);
    m3 = viewed.minusMonths(3);
  }

  // ---------------------------------------------------------------------------------
  // The basis the three shares are taken of
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("The basis")
  class Basis {

    @Test
    @DisplayName("The customer's own monthly target, when they have set one")
    void usesTheTarget() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "40000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertTrue(response.isTargetSet());
      assertEquals("100000.00", response.getBasisAmount());
      assertEquals("Your monthly target", response.getBasis());
    }

    @Test
    @DisplayName("No target falls back to the trailing median of total spend")
    void fallsBackToHistory() {
      // Monthly totals 30,000 / 50,000 / 40,000 -- the median is 40,000, deliberately not the
      // most recent month and deliberately not the mean.
      stubMonth(m1, row(BILLS, "30000.00"));
      stubMonth(m2, row(BILLS, "50000.00"));
      stubMonth(m3, row(BILLS, "40000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertFalse(response.isTargetSet());
      assertEquals("40000.00", response.getBasisAmount());
      assertEquals("Median of your last three months", response.getBasis());
    }

    @Test
    @DisplayName("A zero target is not a target")
    void zeroTargetIgnored() {
      // A saved budget row whose overall limit is zero is a customer who has cleared their target,
      // not one who has committed to spending nothing. Taking it at face value would make every
      // bucket target 0.00 and report the customer as infinitely over.
      stubBudget("0.00");
      stubAllMonths(row(BILLS, "12000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertFalse(response.isTargetSet());
      assertEquals("12000.00", response.getBasisAmount());
    }

    @Test
    @DisplayName("A budget row with no overall limit is not a target")
    void nullOverallLimitIgnored() {
      stubBudget(null);
      stubAllMonths(row(BILLS, "12000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertFalse(response.isTargetSet());
      assertEquals("12000.00", response.getBasisAmount());
    }
  }

  // ---------------------------------------------------------------------------------
  // Which months are sampled, and how a median over them is taken
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("The trailing window")
  class Window {

    @Test
    @DisplayName("Three complete months, never the partial month being viewed")
    void neverTheCurrentMonth() {
      stubAllMonths(row(BILLS, "1000.00"));
      stubCategories();

      service.recommend(USER, today);

      // A recommendation that moves every time the customer buys lunch is not a recommendation.
      verify(transactions, never())
          .aggregateSpendByCategoryBetween(eq(USER), eq(viewed.atDay(1)), any());
    }

    @Test
    @DisplayName("The window is clamped to the compliance look-back, not extended past it")
    void clampedToLookBack() {
      // The look-back is a Compliance position. A customer with a long history must not have more
      // of it read than was approved, even to compute a suggestion.
      properties.setLookbackMonths(2);
      stubMonth(m1, row(BILLS, "10000.00"));
      stubMonth(m2, row(BILLS, "20000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      verify(transactions, never())
          .aggregateSpendByCategoryBetween(eq(USER), eq(m3.atDay(1)), eq(m3.atEndOfMonth()));
      // Median of two months is their midpoint: 15,000, not the 3-month figure.
      assertEquals("15000.00", response.getBasisAmount());
    }

    @Test
    @DisplayName("A category seen in one month only has a median of zero and is dropped")
    void oneOffIsNotAHabit() {
      stubBudget("100000.00");
      stubMonth(m1, row(BILLS, "5000.00"), row(SHOPPING, "90000.00"));
      stubMonth(m2, row(BILLS, "5000.00"));
      stubMonth(m3, row(BILLS, "5000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      // 90,000 of shopping in one month out of three is a sofa, not a habit. Its series is
      // [0, 0, 90000] and the median of that is zero, so it must not appear as a suggested cap.
      assertNull(bucket(response, "WANTS").getCategories());
      assertEquals("0.00", bucket(response, "WANTS").getActualAmount());
      assertEquals("5000.00", bucket(response, "NEEDS").getActualAmount());
    }

    @Test
    @DisplayName("A category first seen in the oldest month is padded across the whole sample")
    void paddedForward() {
      // TRANSPORT appears in the two older months only. Its series must be [0, 4000, 2000] over
      // all three -- not [4000, 2000] over the two it appeared in, which would median to 3,000.
      stubMonth(m1, row(BILLS, "5000.00"));
      stubMonth(m2, row(BILLS, "5000.00"), row(TRANSPORT, "4000.00"));
      stubMonth(m3, row(BILLS, "5000.00"), row(TRANSPORT, "2000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      RecommendationsResponse.CategorySuggestion transport =
          suggestion(bucket(response, "NEEDS"), "TRANSPORT");
      assertEquals("2000.00", transport.getSuggestedCap());
    }
  }

  // ---------------------------------------------------------------------------------
  // The split itself
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("The 50/30/20 split")
  class Split {

    @Test
    @DisplayName("Each bucket's target is its configured share of the basis")
    void targetsAreSharesOfTheBasis() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "40000.00"), row(DINING, "25000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals(3, response.getBuckets().size());
      assertEquals("50000.00", bucket(response, "NEEDS").getTargetAmount());
      assertEquals("30000.00", bucket(response, "WANTS").getTargetAmount());
      assertEquals("20000.00", bucket(response, "SAVINGS").getTargetAmount());
      assertEquals(50, bucket(response, "NEEDS").getTargetPct());
      assertEquals(30, bucket(response, "WANTS").getTargetPct());
      assertEquals(20, bucket(response, "SAVINGS").getTargetPct());
      assertEquals("Needs", bucket(response, "NEEDS").getLabel());
      assertEquals("Wants", bucket(response, "WANTS").getLabel());
      assertEquals("Savings", bucket(response, "SAVINGS").getLabel());
    }

    @Test
    @DisplayName("Actual spend lands in the bucket its category is mapped to")
    void spendLandsInItsBucket() {
      stubBudget("100000.00");
      stubAllMonths(
          row(BILLS, "30000.00"),
          row(TRANSPORT, "10000.00"),
          row(DINING, "15000.00"),
          row(SHOPPING, "10000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals("40000.00", bucket(response, "NEEDS").getActualAmount());
      assertEquals("25000.00", bucket(response, "WANTS").getActualAmount());
      assertEquals(40.0, bucket(response, "NEEDS").getActualPct());
      assertEquals(25.0, bucket(response, "WANTS").getActualPct());
    }

    @Test
    @DisplayName("Savings is the residual PLUS the spend mapped to savings, not minus it")
    void savingsCreditsDebtRepaymentRatherThanChargingItTwice() {
      // The regression this whole bucket exists to prevent. 100,000 target, 40,000 of needs and
      // 20,000 of debt repayment. 40,000 is left unspent and 20,000 went to clearing a loan, so
      // the 20% bucket holds 60,000. Subtracting the repayment instead would report 40,000 while
      // still printing "Debt payments 20,000.00" underneath it -- a panel whose parts contradict
      // its total, and a customer punished for doing the thing the bucket is asking for.
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "40000.00"), row(DEBT, "20000.00"));
      stubCategories();

      RecommendationsResponse.Bucket savings = bucket(service.recommend(USER, today), "SAVINGS");

      assertEquals("60000.00", savings.getActualAmount());
      assertEquals(60.0, savings.getActualPct());
      assertNotNull(savings.getCategories());
      assertEquals("20000.00", suggestion(savings, "DEBT_PAYMENTS").getSuggestedCap());
    }

    @Test
    @DisplayName("Unbucketed spend still comes out of savings -- it is money that left")
    void unbucketedReducesSavings() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "40000.00"), row(OTHER, "5000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals("55000.00", bucket(response, "SAVINGS").getActualAmount());
      assertEquals("5000.00", response.getUnbucketedAmount());
    }

    @Test
    @DisplayName("Spending past the target reports negative savings rather than zero")
    void savingsCanBeNegative() {
      stubBudget("50000.00");
      stubAllMonths(row(BILLS, "40000.00"), row(DINING, "25000.00"));
      stubCategories();

      RecommendationsResponse.Bucket savings = bucket(service.recommend(USER, today), "SAVINGS");

      // Clamping this to zero would flatter the customer with their own money.
      assertEquals("-15000.00", savings.getActualAmount());
      assertEquals(-30.0, savings.getActualPct());
      assertEquals("UNDER", savings.getState());
    }

    @Test
    @DisplayName("Saving more than the 20% share is ON, not OVER")
    void savingsStateIsInverted() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "10000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      // 90,000 saved against a 20,000 share. Telling a customer they have overshot their savings
      // is the kind of correctness that loses the argument with the customer.
      assertEquals("ON", bucket(response, "SAVINGS").getState());
      assertEquals("UNDER", bucket(response, "NEEDS").getState());
      assertEquals("UNDER", bucket(response, "WANTS").getState());
    }

    @Test
    @DisplayName("The three buckets and the unbucketed total add back up to the basis")
    void thePartsSumToTheWhole() {
      // The reconciliation the panel is read against. A customer who adds up the four figures
      // printed in front of them must land on their target exactly; a panel whose parts do not
      // sum to its whole reads as a bug even when every part is defensible. It is also the
      // sharpest statement of the savings rule: the residual and the savings-mapped categories
      // together are what makes this close.
      stubBudget("100000.00");
      stubAllMonths(
          row(BILLS, "30000.00"),
          row(DINING, "18000.00"),
          row(DEBT, "12000.00"),
          row(OTHER, "4000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      BigDecimal sum =
          new BigDecimal(bucket(response, "NEEDS").getActualAmount())
              .add(new BigDecimal(bucket(response, "WANTS").getActualAmount()))
              .add(new BigDecimal(bucket(response, "SAVINGS").getActualAmount()))
              .add(new BigDecimal(response.getUnbucketedAmount()));
      assertEquals(0, new BigDecimal("100000.00").compareTo(sum));

      double pct =
          bucket(response, "NEEDS").getActualPct()
              + bucket(response, "WANTS").getActualPct()
              + bucket(response, "SAVINGS").getActualPct();
      // 30 + 18 + 48, with the unbucketed 4% making up the rest.
      assertEquals(96.0, pct);
      assertEquals("48000.00", bucket(response, "SAVINGS").getActualAmount());
    }

    @Test
    @DisplayName("A spending bucket past its share is OVER, and exactly on it is ON")
    void spendingStates() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "60000.00"), row(DINING, "30000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals("OVER", bucket(response, "NEEDS").getState());
      assertEquals("ON", bucket(response, "WANTS").getState());
    }
  }

  // ---------------------------------------------------------------------------------
  // Categories that map to nothing
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("Unbucketed spend")
  class Unbucketed {

    @Test
    @DisplayName("OTHER and TRANSFERS are reported separately rather than forced into a bucket")
    void reportedSeparately() {
      stubBudget("100000.00");
      stubAllMonths(row(OTHER, "3000.00"), row(TRANSFERS, "7000.00"), row(BILLS, "1000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals("10000.00", response.getUnbucketedAmount());
      assertNotNull(response.getUnbucketed());
      assertEquals(2, response.getUnbucketed().size());
      // Sorted by code, so the panel does not reshuffle between requests.
      assertEquals("OTHER", response.getUnbucketed().get(0).getCategoryCode());
      assertEquals("TRANSFERS", response.getUnbucketed().get(1).getCategoryCode());
      // Forcing the fallback category into a bucket would distort every percentage on the panel.
      assertNull(suggestionOrNull(bucket(response, "NEEDS"), "OTHER"));
    }

    @Test
    @DisplayName("Absent entirely when every category maps")
    void absentWhenEverythingMaps() {
      stubBudget("100000.00");
      stubAllMonths(row(BILLS, "1000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertNull(response.getUnbucketedAmount());
      assertNull(response.getUnbucketed());
    }
  }

  // ---------------------------------------------------------------------------------
  // The envelope around the numbers
  // ---------------------------------------------------------------------------------

  @Nested
  @DisplayName("The response envelope")
  class Envelope {

    @Test
    @DisplayName("The period is the month being viewed, not the trailing window")
    void periodIsTheViewedMonth() {
      stubAllMonths(row(BILLS, "1000.00"));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertEquals(viewed.atDay(1), response.getPeriod().getStart());
      assertEquals(viewed.atEndOfMonth(), response.getPeriod().getEnd());
      assertEquals(viewed.lengthOfMonth(), response.getPeriod().getDaysInPeriod());
      assertEquals("LKR", response.getCurrency());
    }

    @Test
    @DisplayName("A null date means the current month in the configured zone")
    void nullDateMeansNow() {
      // Loose matchers and no month captured from setUp: the only thing null can mean is "today",
      // and a test that pins today fails once a year at midnight on the first of the month.
      when(transactions.aggregateSpendByCategoryBetween(eq(USER), any(), any()))
          .thenReturn(List.<Object[]>of(row(BILLS, "1000.00")));
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, null);

      assertEquals(1, response.getPeriod().getStart().getDayOfMonth());
      assertEquals(
          YearMonth.from(response.getPeriod().getStart()).atEndOfMonth(),
          response.getPeriod().getEnd());
      assertFalse(response.getPeriod().getStart().isBefore(viewed.atDay(1)));
    }

    @Test
    @DisplayName("A customer with no history and no target gets zeros, not percentages of zero")
    void emptyHistory() {
      stubCategories();

      RecommendationsResponse response = service.recommend(USER, today);

      assertFalse(response.isTargetSet());
      assertEquals("0.00", response.getBasisAmount());
      for (RecommendationsResponse.Bucket b : response.getBuckets()) {
        assertEquals("0.00", b.getTargetAmount());
        assertEquals("0.00", b.getActualAmount());
        // Dividing by a zero basis would throw; a percentage of nothing is withheld instead.
        assertNull(b.getActualPct(), b.getKey());
        assertEquals("ON", b.getState());
        assertNull(b.getCategories());
      }
      assertNull(response.getUnbucketedAmount());
    }

    @Test
    @DisplayName("Categories within a bucket are sorted by code")
    void categoriesSorted() {
      stubBudget("100000.00");
      stubAllMonths(row(TRANSPORT, "4000.00"), row(BILLS, "9000.00"));
      stubCategories();

      List<RecommendationsResponse.CategorySuggestion> needs =
          bucket(service.recommend(USER, today), "NEEDS").getCategories();

      assertEquals(2, needs.size());
      assertEquals("BILLS_UTILITIES", needs.get(0).getCategoryCode());
      assertEquals("TRANSPORT", needs.get(1).getCategoryCode());
      assertEquals("Bills & utilities", needs.get(0).getCategoryName());
    }
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private void stubBudget(String overallLimit) {
    when(budgets.findByUserIdAndPeriodStart(USER, viewed.atDay(1)))
        .thenReturn(
            Optional.of(
                BiBudget.builder()
                    .id(UUID.randomUUID())
                    .userId(USER)
                    .periodStart(viewed.atDay(1))
                    .overallLimit(overallLimit == null ? null : new BigDecimal(overallLimit))
                    .build()));
  }

  /** The same rows in each of the three sampled months, so every median is that row's amount. */
  private void stubAllMonths(Object[]... rows) {
    stubMonth(m1, rows);
    stubMonth(m2, rows);
    stubMonth(m3, rows);
  }

  private void stubMonth(YearMonth month, Object[]... rows) {
    when(transactions.aggregateSpendByCategoryBetween(
            USER, month.atDay(1), month.atEndOfMonth()))
        .thenReturn(Arrays.asList(rows));
  }

  private Object[] row(UUID categoryId, String amount) {
    return new Object[] {categoryId, new BigDecimal(amount), 1L};
  }

  private void stubCategories() {
    when(categories.findAllById(any()))
        .thenReturn(
            List.of(
                category(BILLS, "BILLS_UTILITIES", "Bills & utilities"),
                category(TRANSPORT, "TRANSPORT", "Transport"),
                category(DINING, "DINING_OUT", "Dining out"),
                category(SHOPPING, "SHOPPING", "Shopping"),
                category(DEBT, "DEBT_PAYMENTS", "Debt payments"),
                category(OTHER, "OTHER", "Other"),
                category(TRANSFERS, "TRANSFERS", "Transfers")));
  }

  private BiCategory category(UUID id, String code, String nameEn) {
    return BiCategory.builder().id(id).code(code).nameEn(nameEn).build();
  }

  private RecommendationsResponse.Bucket bucket(RecommendationsResponse response, String key) {
    return response.getBuckets().stream()
        .filter(b -> key.equals(b.getKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no bucket " + key));
  }

  private RecommendationsResponse.CategorySuggestion suggestion(
      RecommendationsResponse.Bucket bucket, String code) {
    RecommendationsResponse.CategorySuggestion found = suggestionOrNull(bucket, code);
    if (found == null) {
      throw new AssertionError("no " + code + " in " + bucket.getKey());
    }
    return found;
  }

  private RecommendationsResponse.CategorySuggestion suggestionOrNull(
      RecommendationsResponse.Bucket bucket, String code) {
    if (bucket.getCategories() == null) {
      return null;
    }
    return bucket.getCategories().stream()
        .filter(c -> code.equals(c.getCategoryCode()))
        .findFirst()
        .orElse(null);
  }
}
