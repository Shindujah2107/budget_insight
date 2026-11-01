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

  /** How many customers' bills a biller is behind: counts only, for the Admin Dashboard. */
  long countByBillerId(UUID billerId);

  /** Followed bills per biller: [billerId, count]. */
  @Query(
      "select b.billerId, count(b) from BiUserBill b where b.billerId is not null"
          + " and b.status <> com.moneyreserve.domain.BillStatus.DISMISSED group by b.billerId")
  List<Object[]> countFollowedByBiller();

  /**
   * Recurring payees no biller matches, per cadence: [payeeKey, cadence, customers]. One row per
   * customer and payee, so the counts add up to distinct customers.
   */
  @Query(
      "select b.payeeKey, b.cadence, count(b) from BiUserBill b"
          + " where b.billerId is null and b.payeeKey like 'M:%'"
          + " and b.status in (com.moneyreserve.domain.BillStatus.DETECTED,"
          + " com.moneyreserve.domain.BillStatus.CONFIRMED)"
          + " group by b.payeeKey, b.cadence")
  List<Object[]> countUnknownRecurringPayees();

  /** Customers with at least one bill still followed: the nightly due/missed check covers them. */
  @Query(
      "select distinct b.userId from BiUserBill b"
          + " where b.status <> com.moneyreserve.domain.BillStatus.DISMISSED")
  List<UUID> findLiveUserIds();
}
