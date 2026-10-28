package com.moneyreserve.service;

import com.moneyreserve.dto.RecommendationsResponse;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 50/30/20 guidance from the customer's own history (BI-26).
 *
 * <p>Everything here is derived at read time from {@code bi_transaction} and {@code bi_budget}.
 * Nothing is stored, nothing is cached, and no recommendation is ever written down — which means
 * a customer who re-categorises a transaction sees the guidance move immediately, and a mapping
 * change takes effect on the next request rather than on the next batch job.
 */
public interface RecommendationService {

  /**
   * Guidance for the period containing {@code onDate}.
   *
   * @param onDate any day of the month being viewed; null means the current month in the
   *     configured zone
   */
  RecommendationsResponse recommend(UUID userId, LocalDate onDate);
}
