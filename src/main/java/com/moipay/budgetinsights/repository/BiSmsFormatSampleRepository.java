package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiSmsFormatSample;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiSmsFormatSampleRepository extends JpaRepository<BiSmsFormatSample, UUID> {

  boolean existsByCandidateIdAndUserHash(UUID candidateId, String userHash);

  long countByCandidateId(UUID candidateId);

  /** The candidates a user has samples in, so their counts can be refreshed after a deletion. */
  @Query("SELECT DISTINCT s.candidateId FROM BiSmsFormatSample s WHERE s.userHash = :userHash")
  List<UUID> findCandidateIdsByUserHash(@Param("userHash") String userHash);

  @Modifying
  @Query("DELETE FROM BiSmsFormatSample s WHERE s.userHash = :userHash")
  int deleteByUserHash(@Param("userHash") String userHash);

  @Modifying
  @Query("DELETE FROM BiSmsFormatSample s WHERE s.ttlExpiresAt < :now")
  int deleteExpired(@Param("now") OffsetDateTime now);

  @Modifying
  @Query("DELETE FROM BiSmsFormatSample s WHERE s.candidateId IN :ids")
  int deleteByCandidateIdIn(@Param("ids") Collection<UUID> ids);
}
