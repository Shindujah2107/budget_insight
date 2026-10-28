package com.moneyreserve.repository;

import com.moneyreserve.domain.BiReferenceRelease;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Published reference releases. Insert-only. */
public interface BiReferenceReleaseRepository extends JpaRepository<BiReferenceRelease, Integer> {

  @Query("SELECT COALESCE(MAX(r.version), 0) FROM BiReferenceRelease r")
  int findCurrentVersion();

  Page<BiReferenceRelease> findAllByOrderByVersionDesc(Pageable pageable);

  /**
   * Serialises admin publishes across every instance until the surrounding transaction ends.
   * PostgreSQL only; same mechanism family as {@code PostgresAdvisoryJobLock}.
   */
  @Query(
      value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('bi_reference_publish'))) l",
      nativeQuery = true)
  Integer lockPublish();
}
