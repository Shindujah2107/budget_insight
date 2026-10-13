package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiCategoryRepository extends JpaRepository<BiCategory, UUID> {

  Optional<BiCategory> findByCode(String code);

  Optional<BiCategory> findByCodeAndIsActiveTrue(String code);

  List<BiCategory> findAllByIsActiveTrueOrderBySortOrderAsc();
}
