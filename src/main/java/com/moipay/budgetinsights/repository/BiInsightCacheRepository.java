package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiInsightCache;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiInsightCacheRepository extends JpaRepository<BiInsightCache, UUID> {

  Optional<BiInsightCache> findByUserIdAndCacheKeyAndPeriodStartAndPeriodEnd(
      UUID userId, String cacheKey, LocalDate periodStart, LocalDate periodEnd);

  /**
   * Invalidate every cached aggregate whose period contains the given date.
   *
   * <p>Called inside the same transaction as any write to a transaction row, which is what makes a
   * correction visible immediately rather than on the next scheduled refresh.
   */
  @Modifying
  @Query(
      """
      DELETE FROM BiInsightCache c
      WHERE c.userId = :userId
        AND c.periodStart <= :date
        AND c.periodEnd >= :date
      """)
  int invalidateCovering(@Param("userId") UUID userId, @Param("date") LocalDate date);

  /** When the nightly aggregate job last wrote anything (Overview "last aggregate refresh"). */
  @Query("SELECT MAX(c.computedAt) FROM BiInsightCache c")
  OffsetDateTime findLatestComputedAt();
}
