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

  /**
   * The bill, locked for this transaction (V1.26.0): marking it paid, and the cycles' rollover,
   * run one at a time per bill, so a cycle is settled once however many taps arrive together.
   */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BiUserBill b where b.id = :id and b.userId = :userId")
  Optional<BiUserBill> lockByIdAndUserId(
      @org.springframework.data.repository.query.Param("id") UUID id,
      @org.springframework.data.repository.query.Param("userId") UUID userId);

  /** All the customer's bills, locked in id order (one order everywhere, so no deadlock). */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BiUserBill b where b.userId = :userId order by b.id")
  List<BiUserBill> lockAllByUserId(@org.springframework.data.repository.query.Param("userId") UUID userId);

  long countByUserIdAndStatus(UUID userId, BillStatus status);

  /** How many customers' bills a biller is behind: counts only, for the Admin Dashboard. */
  long countByBillerId(UUID billerId);

  /** Followed bills per biller: [billerId, count]. */
  @Query(
      "select b.billerId, count(b) from BiUserBill b where b.billerId is not null"
          + " and b.status <> com.moneyreserve.domain.BillStatus.DISMISSED"
          + " and b.status <> com.moneyreserve.domain.BillStatus.SUGGESTED group by b.billerId")
  List<Object[]> countFollowedByBiller();

  /**
   * Recurring payees no biller matches, per cadence: [payeeKey, cadence, customers]. One row per
   * customer and payee, so the counts add up to distinct customers.
   */
  @Query(
      "select b.payeeKey, b.cadence, count(b) from BiUserBill b"
          + " where b.billerId is null and b.payeeKey like 'M:%'"
          + " and b.status in (com.moneyreserve.domain.BillStatus.SUGGESTED,"
          + " com.moneyreserve.domain.BillStatus.DETECTED,"
          + " com.moneyreserve.domain.BillStatus.CONFIRMED)"
          + " group by b.payeeKey, b.cadence")
  List<Object[]> countUnknownRecurringPayees();

  /** Customers with at least one bill still followed: the nightly due/missed check covers them. */
  @Query(
      "select distinct b.userId from BiUserBill b"
          + " where b.status <> com.moneyreserve.domain.BillStatus.DISMISSED")
  List<UUID> findLiveUserIds();

  /**
   * The daily check, one page at a time (V1.24.0): customers with a followed bill due by
   * {@code dueBy} (overdue included), in id order, after {@code after}.
   */
  @Query(
      "select distinct b.userId from BiUserBill b"
          + " where b.status <> com.moneyreserve.domain.BillStatus.DISMISSED"
          + " and b.nextDueOn <= :dueBy and b.userId > :after"
          + " order by b.userId")
  List<UUID> findDueUserIdsAfter(
      @org.springframework.data.repository.query.Param("dueBy") java.time.LocalDate dueBy,
      @org.springframework.data.repository.query.Param("after") UUID after,
      org.springframework.data.domain.Pageable page);
}
