package com.moneyreserve.repository;

import com.moneyreserve.domain.BiRefreshToken;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiRefreshTokenRepository extends JpaRepository<BiRefreshToken, UUID> {

  Optional<BiRefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query(
      "UPDATE BiRefreshToken t SET t.revokedAt = :now "
          + "WHERE t.familyId = :family AND t.revokedAt IS NULL")
  int revokeFamily(@Param("family") UUID family, @Param("now") OffsetDateTime now);

  @Modifying
  @Query("DELETE FROM BiRefreshToken t WHERE t.userId = :user")
  int deleteByUserId(@Param("user") UUID user);
}
