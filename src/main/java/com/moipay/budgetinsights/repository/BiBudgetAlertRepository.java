package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiBudgetAlert;
import com.moipay.budgetinsights.domain.BudgetAlertType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiBudgetAlertRepository extends JpaRepository<BiBudgetAlert, UUID> {

  /**
   * Every alert already raised for this customer in this period.
   *
   * <p>Read once per evaluation and matched in memory rather than asked per category: a customer
   * with caps on eight categories would otherwise cost eight existence checks on the write path,
   * which runs inside the ingest transaction.
   */
  List<BiBudgetAlert> findAllByUserIdAndPeriodStart(UUID userId, LocalDate periodStart);

  /** The read path: newest first, for the alerts endpoint. */
  List<BiBudgetAlert> findAllByUserIdAndPeriodStartOrderByRaisedAtDesc(
      UUID userId, LocalDate periodStart);

  /** Always resolve by id together with the owner, never by id alone. */
  java.util.Optional<BiBudgetAlert> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndPeriodStartAndCategoryIdAndAlertType(
      UUID userId, LocalDate periodStart, UUID categoryId, BudgetAlertType alertType);
}
