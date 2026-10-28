package com.moneyreserve.repository;

import com.moneyreserve.domain.BiDataExport;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BiDataExportRepository extends JpaRepository<BiDataExport, UUID> {

  /**
   * The only way an export is ever read.
   *
   * <p>Scoped by owner in the query rather than checked after the fetch. An export is the single
   * most valuable row in this service — one customer's entire financial history in one place — so
   * the id alone must never be enough, and a finder that could return somebody else's row should
   * not exist to be called by mistake.
   */
  Optional<BiDataExport> findByIdAndUserId(UUID id, UUID userId);

  /** The customer's own exports, newest first. */
  java.util.List<BiDataExport> findAllByUserIdOrderByRequestedAtDesc(UUID userId);

  /**
   * TTL purge, run by the retention job.
   *
   * <p>{@code @Transactional} here rather than only on the caller: under PostgreSQL the job body
   * already runs inside the advisory lock's transaction and this joins it, but under H2 the
   * fallback lock takes no transaction at all and a modifying query without one fails.
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM BiDataExport e WHERE e.expiresAt < :now")
  int deleteExpired(@Param("now") OffsetDateTime now);
}
