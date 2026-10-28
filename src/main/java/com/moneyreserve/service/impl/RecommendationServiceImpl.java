package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBudget;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.SpendBucket;
import com.moneyreserve.dto.AdminBudgetPolicyState;
import com.moneyreserve.dto.PeriodDto;
import com.moneyreserve.dto.RecommendationsResponse;
import com.moneyreserve.repository.BiBudgetRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.InsightsMapper;
import com.moneyreserve.service.RecommendationService;
import com.moneyreserve.service.calc.BudgetMath;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 50/30/20 recommendations (BI-26).
 *
 * <p><b>The split is of the customer's monthly target, not their income.</b> This service never
 * sees income. Splitting the target is what makes the savings bucket observable at all: it is
 * target minus spend — money deliberately not spent — rather than a total of outgoing categories
 * that would report a diligent saver as saving nothing. Where no target has been set, the trailing
 * median stands in for one, which is the same basis BI-24 already uses to suggest a first target.
 *
 * <p><b>Per-category caps are the category's own trailing median.</b> §14: "suggested caps from the
 * trailing three-month median, taken only from within the six-month window". A median rather than a
 * mean for the reason {@link BudgetMath#median} gives — one month with a wedding in it should not
 * set a cap the customer can never keep.
 *
 * <p>Three complete months before the one being viewed, never the current partial one: a
 * recommendation that moves every time the customer buys lunch is not a recommendation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

  private final InsightsProperties properties;
  private final InsightsMapper mapper;
  private final BiBudgetRepository budgets;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BudgetPolicyProvider policy;

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final String BASIS_TARGET = "Your monthly target";

  @Override
  @Transactional(readOnly = true)
  public RecommendationsResponse recommend(UUID userId, LocalDate onDate) {

    LocalDate today = LocalDate.now(properties.zoneId());
    YearMonth viewed = YearMonth.from(onDate == null ? today : onDate);
    InsightsPeriod period = InsightsPeriod.ofMonth(viewed);

    InsightsProperties.Recommendations config = properties.getRecommendations();
    // Split and period from the budget policy (Admin Dashboard); YAML only as its fallback.
    AdminBudgetPolicyState rules = policy.current();
    int months = Math.max(1, rules.trailingMonths());

    // The oldest month we may look at. The look-back is a Compliance position, so the trailing
    // window is clamped to it rather than allowed to reach further back on a customer with a long
    // history -- see §14: "taken only from within the six-month window".
    YearMonth earliest = YearMonth.from(today.minusMonths(properties.getLookbackMonths()));

    Map<UUID, List<BigDecimal>> monthlyByCategory = new HashMap<>();
    List<BigDecimal> monthlyTotals = new ArrayList<>(months);
    int sampled = 0;

    for (int back = 1; back <= months; back++) {
      YearMonth month = viewed.minusMonths(back);
      if (month.isBefore(earliest)) {
        continue;
      }
      sampled++;
      BigDecimal monthTotal = BigDecimal.ZERO;
      Map<UUID, BigDecimal> thisMonth = new HashMap<>();
      for (Object[] row :
          transactions.aggregateSpendByCategoryBetween(
              userId, month.atDay(1), month.atEndOfMonth())) {
        UUID categoryId = (UUID) row[0];
        BigDecimal amount = row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1];
        thisMonth.put(categoryId, amount);
        monthTotal = monthTotal.add(amount);
      }
      monthlyTotals.add(monthTotal);
      // Every category seen in any sampled month needs an entry for every month, including the
      // months it was absent from -- a median over only the months a category appeared in would
      // report a one-off purchase as a monthly habit.
      for (UUID categoryId : thisMonth.keySet()) {
        monthlyByCategory.computeIfAbsent(categoryId, k -> new ArrayList<>());
      }
      for (Map.Entry<UUID, List<BigDecimal>> entry : monthlyByCategory.entrySet()) {
        entry.getValue().add(thisMonth.getOrDefault(entry.getKey(), BigDecimal.ZERO));
      }
    }

    // Pad any category first seen in a later month back to the full sample length.
    for (List<BigDecimal> series : monthlyByCategory.values()) {
      while (series.size() < sampled) {
        series.add(0, BigDecimal.ZERO);
      }
    }

    Map<UUID, BigDecimal> medianByCategory = new HashMap<>();
    for (Map.Entry<UUID, List<BigDecimal>> entry : monthlyByCategory.entrySet()) {
      BigDecimal median = BudgetMath.median(entry.getValue());
      if (median.signum() > 0) {
        medianByCategory.put(entry.getKey(), median);
      }
    }

    BigDecimal medianTotal = BudgetMath.median(monthlyTotals);

    Optional<BiBudget> budget = budgets.findByUserIdAndPeriodStart(userId, period.start());
    BigDecimal target =
        budget
            .map(BiBudget::getOverallLimit)
            .filter(limit -> limit != null && limit.signum() > 0)
            .orElse(null);
    boolean targetSet = target != null;
    BigDecimal basisAmount = targetSet ? target : medianTotal;

    Map<String, SpendBucket> bucketByCode = bucketByCode(config);
    Map<UUID, BiCategory> categoryById = new HashMap<>();
    for (BiCategory category : categories.findAllById(medianByCategory.keySet())) {
      categoryById.put(category.getId(), category);
    }

    Map<SpendBucket, BigDecimal> spendByBucket = new EnumMap<>(SpendBucket.class);
    Map<SpendBucket, List<RecommendationsResponse.CategorySuggestion>> catsByBucket =
        new EnumMap<>(SpendBucket.class);
    for (SpendBucket bucket : SpendBucket.values()) {
      spendByBucket.put(bucket, BigDecimal.ZERO);
      catsByBucket.put(bucket, new ArrayList<>());
    }
    BigDecimal unbucketed = BigDecimal.ZERO;
    List<RecommendationsResponse.CategorySuggestion> unbucketedCats = new ArrayList<>();

    for (Map.Entry<UUID, BigDecimal> entry : medianByCategory.entrySet()) {
      BiCategory category = categoryById.get(entry.getKey());
      if (category == null) {
        continue;
      }
      RecommendationsResponse.CategorySuggestion suggestion =
          RecommendationsResponse.CategorySuggestion.builder()
              .categoryCode(category.getCode())
              .categoryName(category.getNameEn())
              .suggestedCap(mapper.money(entry.getValue()))
              .build();
      SpendBucket bucket = bucketByCode.get(category.getCode());
      if (bucket == null) {
        unbucketed = unbucketed.add(entry.getValue());
        unbucketedCats.add(suggestion);
      } else {
        spendByBucket.put(bucket, spendByBucket.get(bucket).add(entry.getValue()));
        catsByBucket.get(bucket).add(suggestion);
      }
    }

    // Everything that actually left the account in a typical month, the categories mapped to
    // savings included -- a debt repayment is money out, and counting it here is what keeps the
    // residual below an honest "what is left over".
    BigDecimal outflowTotal = unbucketed;
    for (SpendBucket bucket : SpendBucket.values()) {
      outflowTotal = outflowTotal.add(spendByBucket.get(bucket));
    }

    // The savings bucket is the residual -- basis minus everything spent -- plus the spend already
    // mapped to savings. The rule puts debt repayment in the 20% alongside money set aside, so a
    // customer clearing a loan has to be credited for it rather than charged twice: once by having
    // it taken out of the residual, and again by seeing it listed under a bucket whose figure
    // excluded it, so that the categories printed under the bucket did not add up to the bucket.
    // The result can still be negative, and is reported that way: a customer spending past their
    // target is saving less than nothing, and clamping to zero would flatter them with their own
    // money.
    BigDecimal savedInCategories = spendByBucket.get(SpendBucket.SAVINGS);
    spendByBucket.put(SpendBucket.SAVINGS, savedInCategories.add(basisAmount.subtract(outflowTotal)));

    List<RecommendationsResponse.Bucket> buckets = new ArrayList<>(3);
    buckets.add(bucket(SpendBucket.NEEDS, rules.needsPct(), basisAmount, spendByBucket, catsByBucket));
    buckets.add(bucket(SpendBucket.WANTS, rules.wantsPct(), basisAmount, spendByBucket, catsByBucket));
    buckets.add(bucket(SpendBucket.SAVINGS, rules.savingsPct(), basisAmount, spendByBucket, catsByBucket));

    unbucketedCats.sort(Comparator.comparing(RecommendationsResponse.CategorySuggestion::getCategoryCode));

    log.info(
        "Insights recommendations userId={} month={} targetSet={} sampledMonths={}",
        userId,
        viewed,
        targetSet,
        sampled);

    return RecommendationsResponse.builder()
        .period(
            PeriodDto.builder()
                .start(period.start())
                .end(period.end())
                .daysInPeriod(period.days())
                .build())
        .currency(properties.getCurrency())
        .basisAmount(mapper.money(basisAmount))
        .basis(targetSet ? BASIS_TARGET : BudgetServiceImpl.basisText(months))
        .periodMonths(months)
        .targetSet(targetSet)
        .buckets(buckets)
        .unbucketedAmount(unbucketed.signum() > 0 ? mapper.money(unbucketed) : null)
        .unbucketed(unbucketedCats.isEmpty() ? null : unbucketedCats)
        .build();
  }

  // ---------------------------------------------------------------------------------

  private RecommendationsResponse.Bucket bucket(
      SpendBucket key,
      int targetPct,
      BigDecimal basisAmount,
      Map<SpendBucket, BigDecimal> spendByBucket,
      Map<SpendBucket, List<RecommendationsResponse.CategorySuggestion>> catsByBucket) {

    BigDecimal actual = spendByBucket.get(key);
    BigDecimal targetAmount =
        basisAmount.multiply(BigDecimal.valueOf(targetPct)).divide(HUNDRED, 2, RoundingMode.HALF_UP);

    Double actualPct = null;
    if (basisAmount.signum() > 0) {
      actualPct =
          actual
              .multiply(HUNDRED)
              .divide(basisAmount, 1, RoundingMode.HALF_UP)
              .doubleValue();
    }

    List<RecommendationsResponse.CategorySuggestion> cats = catsByBucket.get(key);
    cats.sort(Comparator.comparing(RecommendationsResponse.CategorySuggestion::getCategoryCode));

    return RecommendationsResponse.Bucket.builder()
        .key(key.name())
        .label(label(key))
        .targetPct(targetPct)
        .targetAmount(mapper.money(targetAmount))
        .actualAmount(mapper.money(actual))
        .actualPct(actualPct)
        .state(state(key, actual, targetAmount))
        .categories(cats.isEmpty() ? null : cats)
        .build();
  }

  /**
   * Whether the bucket is where it should be.
   *
   * <p>The sense is inverted for savings, and that is the whole point of the bucket: spending more
   * than the needs share is over, but saving more than the savings share is not a problem to be
   * reported as one.
   */
  private String state(SpendBucket key, BigDecimal actual, BigDecimal targetAmount) {
    int cmp = actual.compareTo(targetAmount);
    if (cmp == 0) {
      return "ON";
    }
    if (key == SpendBucket.SAVINGS) {
      return cmp > 0 ? "ON" : "UNDER";
    }
    return cmp > 0 ? "OVER" : "UNDER";
  }

  private String label(SpendBucket key) {
    switch (key) {
      case NEEDS:
        return "Needs";
      case WANTS:
        return "Wants";
      default:
        return "Savings";
    }
  }

  /**
   * Category code to bucket: the bucket set on each category in the Admin Dashboard
   * ({@code bi_category.spend_bucket}, V1.8.0). Only when no category has one (a schema without
   * the V1.8.0 backfill) the YAML lists are used; a code in more than one list takes the first.
   */
  private Map<String, SpendBucket> bucketByCode(InsightsProperties.Recommendations config) {
    Map<String, SpendBucket> out = new LinkedHashMap<>();
    for (BiCategory category : categories.findAll()) {
      SpendBucket bucket = parseBucket(category.getSpendBucket());
      if (bucket != null) {
        out.put(category.getCode(), bucket);
      }
    }
    if (!out.isEmpty()) {
      return out;
    }
    put(out, config.getNeeds(), SpendBucket.NEEDS);
    put(out, config.getWants(), SpendBucket.WANTS);
    put(out, config.getSavings(), SpendBucket.SAVINGS);
    return out;
  }

  private static SpendBucket parseBucket(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return SpendBucket.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private void put(Map<String, SpendBucket> out, List<String> codes, SpendBucket bucket) {
    if (codes == null) {
      return;
    }
    for (String code : codes) {
      if (code != null && !code.isBlank()) {
        out.putIfAbsent(code.trim().toUpperCase(Locale.ROOT), bucket);
      }
    }
  }
}
