package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiMerchantCategoryMap;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiMerchantCategoryMapRepository;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import com.moneyreserve.service.calc.MerchantRuleMatcher;
import com.moneyreserve.service.calc.MessageTypeCategories;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorisation in five layers:
 *
 * <ol>
 *   <li><b>L0 message type</b>: types configured in {@code
 *       moneyreserve.insights.categorisation.message-type-categories} (ATM receipts, CRM deposits,
 *       account charges) are decided by type alone, because their "merchant" is a location or a fee
 *       description;
 *   <li><b>L1 merchant clean-up</b>: {@link com.moneyreserve.service.calc.MerchantText};
 *   <li><b>L2–L4 rules</b>: channel/fee, brand/biller and generic keyword rules in the merchant map,
 *       all tried, the winner chosen by level, then specificity, then priority ({@link
 *       MerchantRuleMatcher#ranking});
 *   <li><b>L5</b>: {@code OTHER}, reported to the admin as uncategorised merchants.
 * </ol>
 *
 * <p>A rule whose category is inactive is skipped and the next-best rule is used.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategorisationServiceImpl implements CategorisationService {

  private final BiMerchantCategoryMapRepository merchantMaps;
  private final BiCategoryRepository categories;
  private final InsightsProperties properties;

  /**
   * The rule matching shared with the admin's "Test this rule" ({@link MerchantRuleMatcher}),
   * including its cache of compiled patterns. A field, not a constructor dependency: it holds no
   * configuration, only the cache.
   */
  private final MerchantRuleMatcher matcher = new MerchantRuleMatcher();

  @Override
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public BiCategory categorise(CategorisationInput input) {
    if (input == null) {
      return fallback();
    }

    // ---- L0: decided by message type alone.
    Optional<BiCategory> byType = byMessageType(input.messageType());
    if (byType.isPresent()) {
      return byType.get();
    }

    if (MerchantRuleMatcher.normalise(input.merchant()) == null) {
      return fallback();
    }

    // ---- L1–L4: every active rule tried, best match first.
    List<MerchantRuleMatcher.Match<BiMerchantCategoryMap>> ranked =
        matcher.ranked(
            merchantMaps.findAllByIsActiveTrueOrderByPriorityAsc(),
            r -> r.getPriority() == null ? null : (int) r.getPriority(),
            BiMerchantCategoryMap::getMatchType,
            BiMerchantCategoryMap::getMerchantPattern,
            input.merchant(),
            Comparator.comparing(
                BiMerchantCategoryMap::getId, Comparator.nullsLast(Comparator.<UUID>naturalOrder())));

    for (MerchantRuleMatcher.Match<BiMerchantCategoryMap> match : ranked) {
      BiMerchantCategoryMap rule = match.rule();
      Optional<BiCategory> hit = categories.findById(rule.getCategoryId());
      if (hit.isPresent() && Boolean.TRUE.equals(hit.get().getIsActive())) {
        return hit.get();
      }
      // A rule pointing at a retired category is a data defect in reference data we control,
      // so it is an error even though the request itself still succeeds on the next rule.
      log.error("Insights merchant rule {} points at an inactive category; skipping", rule.getId());
    }
    // ---- L5. Deliberately not logged. This runs once per ingested message, so a line here would be
    // the highest-volume statement in the service; the admin's uncategorised-merchant report
    // aggregates these instead.
    return fallback();
  }

  @Override
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public BiCategory fallback() {
    return categories
        .findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Budget Insights reference data is incomplete: category '"
                        + BiCategory.OTHER_CODE
                        + "' is missing or inactive. Check migration V1.0.1."));
  }

  @Override
  public void refresh() {
    matcher.clear();
    log.info("Insights merchant-rule matcher cache cleared; rules reload on next use");
  }

  // ---------------------------------------------------------------------------------

  private Optional<BiCategory> byMessageType(String messageType) {
    Map<String, String> configured =
        properties == null ? null : properties.getCategorisation().getMessageTypeCategories();
    String code = MessageTypeCategories.codeFor(configured, messageType).orElse(null);
    if (code == null) {
      return Optional.empty();
    }
    if (BiCategory.OTHER_CODE.equals(code)) {
      return Optional.of(fallback());
    }
    Optional<BiCategory> category = categories.findByCodeAndIsActiveTrue(code);
    if (category.isEmpty()) {
      log.error(
          "Insights message type {} is configured for category {}, which is missing or inactive; using merchant rules",
          messageType,
          code);
    }
    return category;
  }
}
