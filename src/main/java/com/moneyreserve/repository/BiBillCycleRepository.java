package com.moneyreserve.repository;

import com.moneyreserve.domain.BiBillCycle;
import com.moneyreserve.domain.BillCycleStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiBillCycleRepository extends JpaRepository<BiBillCycle, UUID> {

  Optional<BiBillCycle> findByUserBillIdAndDueOn(UUID userBillId, LocalDate dueOn);

  Optional<BiBillCycle> findByIdAndUserId(UUID id, UUID userId);

  Optional<BiBillCycle> findByTransactionId(UUID transactionId);

  /** A bill's cycles, newest first. */
  List<BiBillCycle> findAllByUserBillIdOrderByDueOnDesc(UUID userBillId);

  List<BiBillCycle> findAllByUserBillIdAndStatusIn(UUID userBillId, Collection<BillCycleStatus> statuses);

  /** The customer's cycles from a date on (the bills list needs only the recent ones). */
  List<BiBillCycle> findAllByUserIdAndDueOnGreaterThanEqual(UUID userId, LocalDate from);

  @Modifying
  @Query("delete from BiBillCycle c where c.userBillId = :billId")
  void deleteAllForBill(@Param("billId") UUID billId);
}
