package com.moneyreserve.repository;

import com.moneyreserve.domain.BiUnmatchedSmsStat;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BiUnmatchedSmsStatRepository extends JpaRepository<BiUnmatchedSmsStat, UUID> {

  /** This device's rows for the days in a batch, so a retried upload updates instead of adding. */
  List<BiUnmatchedSmsStat> findByDeviceHashAndStatDayIn(String deviceHash, Collection<LocalDate> days);

  /** One row per sender since {@code from}: messages, devices, last day seen. */
  interface SenderTotals {
    String getSenderId();

    Long getMessages();

    Long getDevices();

    LocalDate getLastDay();
  }

  @Query(
      "SELECT s.senderId AS senderId, SUM(s.msgCount) AS messages, "
          + "COUNT(DISTINCT s.deviceHash) AS devices, MAX(s.statDay) AS lastDay "
          + "FROM BiUnmatchedSmsStat s WHERE s.statDay >= :from GROUP BY s.senderId")
  List<SenderTotals> totalsSince(@Param("from") LocalDate from);

  @Modifying
  @Transactional
  @Query("DELETE FROM BiUnmatchedSmsStat s WHERE s.statDay < :before")
  int deleteOlderThan(@Param("before") LocalDate before);
}
