package com.moneyreserve.repository;

import com.moneyreserve.domain.BiBudget;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiBudgetRepository extends JpaRepository<BiBudget, UUID> {

  Optional<BiBudget> findByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);

  /**
   * The most recent target strictly before a period, which is what a carry-forward copies.
   *
   * <p>The bound is what makes this correct. Simply taking the customer's latest budget would,
   * once the period being saved has one of its own, return that same budget — and a period cannot
   * be carried forward into itself, so an ordinary re-save would be refused with "there is no
   * earlier budget to carry forward".
   */
  Optional<BiBudget> findFirstByUserIdAndPeriodStartLessThanOrderByPeriodStartDesc(
      UUID userId, LocalDate periodStart);
}
