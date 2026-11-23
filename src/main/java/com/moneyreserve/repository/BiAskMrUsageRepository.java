package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAskMrUsage;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiAskMrUsageRepository extends JpaRepository<BiAskMrUsage, BiAskMrUsage.Key> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM BiAskMrUsage u WHERE u.day = :day AND u.question = :question")
  Optional<BiAskMrUsage> lock(@Param("day") LocalDate day, @Param("question") String question);

  List<BiAskMrUsage> findByDayBetweenOrderByDayDescQuestionAsc(LocalDate from, LocalDate to);
}
