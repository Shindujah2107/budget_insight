package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAuthCodeRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiAuthCodeRequestRepository extends JpaRepository<BiAuthCodeRequest, UUID> {

  long countByPhoneHashAndCreatedAtAfter(String phoneHash, OffsetDateTime after);

  long countByIpHashAndCreatedAtAfter(String ipHash, OffsetDateTime after);

  long countByCreatedAtAfter(OffsetDateTime after);

  @Modifying
  @Query("DELETE FROM BiAuthCodeRequest r WHERE r.createdAt < :before")
  int deleteOlderThan(@Param("before") OffsetDateTime before);
}
