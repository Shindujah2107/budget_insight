package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiConsent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiConsentRepository extends JpaRepository<BiConsent, UUID> {

  /** Current consent state is the latest row. Ingest checks this on every call. */
  Optional<BiConsent> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

  /** Customers with a withdrawal recorded since {@code since} (they may have granted again). */
  @Query("SELECT DISTINCT c.userId FROM BiConsent c WHERE c.withdrawnAt >= :since")
  List<UUID> findUserIdsWithdrawnSince(@Param("since") OffsetDateTime since);

  /** Customers whose current (latest) consent row is granted and not withdrawn. */
  @Query(
      """
      SELECT COUNT(c) FROM BiConsent c
      WHERE c.grantedAt IS NOT NULL AND c.withdrawnAt IS NULL
        AND (:tenant IS NULL OR c.tenantId = :tenant)
        AND c.createdAt = (SELECT MAX(c2.createdAt) FROM BiConsent c2 WHERE c2.userId = c.userId)
      """)
  long countActiveConsents(@Param("tenant") UUID tenant);
}
