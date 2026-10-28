package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAuthOtp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiAuthOtpRepository extends JpaRepository<BiAuthOtp, UUID> {

  Optional<BiAuthOtp> findFirstByPhoneOrderByCreatedAtDesc(String phone);

  List<BiAuthOtp> findByPhoneAndConsumedAtIsNull(String phone);

  long countByPhoneAndCreatedAtAfter(String phone, OffsetDateTime since);

  @Modifying
  @Query("DELETE FROM BiAuthOtp o WHERE o.phone = :phone")
  int deleteByPhone(@Param("phone") String phone);

  @Modifying
  @Query("DELETE FROM BiAuthOtp o WHERE o.createdAt < :before")
  int deleteCreatedBefore(@Param("before") OffsetDateTime before);
}
