package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiBudgetCategoryLimit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BiBudgetCategoryLimitRepository
    extends JpaRepository<BiBudgetCategoryLimit, UUID> {

  List<BiBudgetCategoryLimit> findAllByBudgetId(UUID budgetId);

  void deleteAllByBudgetId(UUID budgetId);

  /** Admin usage: {@code [categoryId, limits]} across every customer budget. */
  @Query("SELECT l.categoryId, COUNT(l) FROM BiBudgetCategoryLimit l GROUP BY l.categoryId")
  List<Object[]> countByCategory();

  long countByCategoryId(UUID categoryId);
}
