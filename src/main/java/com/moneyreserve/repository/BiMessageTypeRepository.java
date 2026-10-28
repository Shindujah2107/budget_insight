package com.moneyreserve.repository;

import com.moneyreserve.domain.BiMessageType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiMessageTypeRepository extends JpaRepository<BiMessageType, String> {

  List<BiMessageType> findAllByFixedCategoryCodeIsNotNull();
}
