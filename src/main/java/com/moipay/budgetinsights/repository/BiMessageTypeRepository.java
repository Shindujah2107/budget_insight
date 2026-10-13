package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiMessageType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiMessageTypeRepository extends JpaRepository<BiMessageType, String> {

  List<BiMessageType> findAllByFixedCategoryCodeIsNotNull();
}
