package com.moneyreserve.repository;

import com.moneyreserve.domain.BiMerchantCategoryMap;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiMerchantCategoryMapRepository
    extends JpaRepository<BiMerchantCategoryMap, UUID> {

  /** Evaluation order for categorisation: lowest priority value first. */
  List<BiMerchantCategoryMap> findAllByIsActiveTrueOrderByPriorityAsc();

  long countByCategoryId(UUID categoryId);
}
