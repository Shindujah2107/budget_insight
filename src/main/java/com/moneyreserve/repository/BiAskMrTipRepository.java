package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAskMrTip;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiAskMrTipRepository extends JpaRepository<BiAskMrTip, UUID> {

  List<BiAskMrTip> findByIsActiveTrueOrderBySortOrderAscCodeAsc();
}
