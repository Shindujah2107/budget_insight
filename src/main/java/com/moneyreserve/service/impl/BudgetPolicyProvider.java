package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBudgetPolicy;
import com.moneyreserve.dto.AdminBudgetPolicyState;
import com.moneyreserve.repository.BiBudgetPolicyRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The live budget policy for the customer-facing services: budget status (pace tolerance), the
 * default category alert threshold, which alert types are raised, and the 50/30/20 split and
 * period.
 *
 * <p>Read from {@code bi_budget_policy} (published from the Admin Dashboard) and cached for 30
 * seconds, so a mobile request does not query it; a publish evicts this instance's copy at once and
 * the other instances pick it up within the TTL. If the row is missing (a schema built without
 * Flyway) the YAML values are used, which is also what the V1.9.0 seed holds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BudgetPolicyProvider {

  private static final Duration TTL = Duration.ofSeconds(30);

  private final BiBudgetPolicyRepository policies;
  private final InsightsProperties properties;

  private volatile AdminBudgetPolicyState cached;
  private volatile Instant cachedAt = Instant.EPOCH;

  public AdminBudgetPolicyState current() {
    AdminBudgetPolicyState held = cached;
    Instant now = Instant.now();
    if (held == null || now.isAfter(cachedAt.plus(TTL))) {
      held = load();
      cached = held;
      cachedAt = now;
    }
    return held;
  }

  /** After a publish on this instance. */
  public void evict() {
    cached = null;
  }

  /** The published policy straight from the table (or the YAML fallback); not cached. */
  public AdminBudgetPolicyState load() {
    try {
      return policies.findById(BiBudgetPolicy.ID).map(BudgetPolicyProvider::toState).orElseGet(this::fallback);
    } catch (RuntimeException ex) {
      // Never let a policy read take a customer screen down: the YAML values are the seed values.
      log.error("Budget policy could not be read; using configured defaults", ex);
      return fallback();
    }
  }

  /** The values in application config (the V1.9.0 seed); used when the row is missing. */
  public AdminBudgetPolicyState fallback() {
    InsightsProperties.Recommendations r = properties.getRecommendations();
    return new AdminBudgetPolicyState(
        r.getNeedsPct(),
        r.getWantsPct(),
        r.getSavingsPct(),
        Math.max(1, r.getTrailingMonths()),
        properties.getDefaultCategoryAlertThresholdPct(),
        Math.max(0, properties.getPaceTolerancePct()),
        true,
        true,
        true);
  }

  static AdminBudgetPolicyState toState(BiBudgetPolicy p) {
    return new AdminBudgetPolicyState(
        p.getNeedsPct(),
        p.getWantsPct(),
        p.getSavingsPct(),
        p.getTrailingMonths(),
        p.getDefaultAlertPct(),
        p.getPaceTolerancePct(),
        Boolean.TRUE.equals(p.getAlertCategoryApproaching()),
        Boolean.TRUE.equals(p.getAlertCategoryExceeded()),
        Boolean.TRUE.equals(p.getAlertBudgetExceeded()));
  }
}
