package com.moneyreserve.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.domain.BiInsightCache;
import com.moneyreserve.dto.SpendingBreakdownResponse;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of {@code bi_insight_cache} (BI-29).
 *
 * <p>The table has been written to since R1 — but only to be emptied. {@code BI-22} invalidates
 * every overlapping row inside the same transaction as any write to the ledger, which has worked
 * correctly and pointlessly, because nothing ever put a row there to invalidate. This class is the
 * other half.
 *
 * <p><b>Whole calendar months only.</b> The unique key is (user, key, start, end), so caching every
 * range a client asks for would let one customer with a date picker fill the table with rows nobody
 * reads twice. A month is the period the screen actually requests, the period the refresh job
 * precomputes, and the only one worth keeping. Custom ranges compute and are not stored — which is
 * also why a cache miss must stay cheap.
 *
 * <p><b>The key carries a version.</b> A cached payload is a serialised DTO, and a DTO that gains a
 * field would otherwise be read back from rows written before the field existed. Bumping {@code
 * KEY} orphans every old row at once; the retention of those rows is the invalidation query's
 * business, not a migration's.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightsAggregateCache {

  /** Bump the suffix whenever {@link SpendingBreakdownResponse} changes shape. */
  static final String KEY = "breakdown:v1";

  private final BiInsightCacheRepository caches;
  private final ObjectMapper objectMapper;

  /** True when this period is a whole calendar month, and so worth storing. */
  public boolean cacheable(InsightsPeriod period) {
    return period != null
        && !period.isInverted()
        && period.start().getDayOfMonth() == 1
        && period.end().equals(YearMonth.from(period.start()).atEndOfMonth());
  }

  @Transactional(readOnly = true)
  public Optional<SpendingBreakdownResponse> read(UUID userId, InsightsPeriod period) {
    if (!cacheable(period)) {
      return Optional.empty();
    }
    return caches
        .findByUserIdAndCacheKeyAndPeriodStartAndPeriodEnd(
            userId, KEY, period.start(), period.end())
        .flatMap(this::deserialise);
  }

  /**
   * Stores a computed breakdown.
   *
   * <p>{@code REQUIRES_NEW} because the caller is a read. The breakdown method on
   * {@code InsightsQueryServiceImpl} is {@code @Transactional(readOnly = true)}, and an insert
   * inside a read-only transaction is
   * refused by PostgreSQL and silently dropped by Hibernate's manual flush — a cache that appeared
   * to work locally and never stored a row in production.
   *
   * <p>An upsert rather than an insert. The refresh job and a read-through miss can both arrive at
   * the same (user, period), and the unique constraint would turn the second one into an error on
   * a path whose whole purpose is to be invisible.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(UUID userId, InsightsPeriod period, SpendingBreakdownResponse body) {
    if (!cacheable(period) || body == null) {
      return;
    }
    String payload;
    try {
      payload = objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException ex) {
      // A cache is an optimisation. Failing the customer's read because the optimisation could not
      // be stored would turn a performance feature into an availability one.
      log.error("Could not serialise an insights breakdown for caching; serving uncached", ex);
      return;
    }

    BiInsightCache row =
        caches
            .findByUserIdAndCacheKeyAndPeriodStartAndPeriodEnd(
                userId, KEY, period.start(), period.end())
            .orElseGet(
                () ->
                    BiInsightCache.builder()
                        .id(UUID.randomUUID())
                        .userId(userId)
                        .cacheKey(KEY)
                        .periodStart(period.start())
                        .periodEnd(period.end())
                        .build());

    row.setPayload(payload);
    row.setComputedAt(OffsetDateTime.now());
    caches.save(row);
  }

  private Optional<SpendingBreakdownResponse> deserialise(BiInsightCache row) {
    try {
      return Optional.of(
          objectMapper.readValue(row.getPayload(), SpendingBreakdownResponse.class));
    } catch (JsonProcessingException ex) {
      // A row written by an older shape than the key claims. Treated as a miss rather than an
      // error: the caller recomputes and overwrites it, and the customer sees correct figures
      // either way.
      log.error("Discarding an unreadable insights cache row for period {}", row.getPeriodStart());
      return Optional.empty();
    }
  }
}
