package com.moneyreserve.repository;

import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BiUserBillRepository extends JpaRepository<BiUserBill, UUID> {

  List<BiUserBill> findAllByUserIdOrderByNextDueOnAsc(UUID userId);

  Optional<BiUserBill> findByIdAndUserId(UUID id, UUID userId);

  long countByUserIdAndStatus(UUID userId, BillStatus status);

  /** Customers with at least one bill still followed: the nightly due/missed check covers them. */
  @Query(
      "select distinct b.userId from BiUserBill b"
          + " where b.status <> com.moneyreserve.domain.BillStatus.DISMISSED")
  List<UUID> findLiveUserIds();
}
