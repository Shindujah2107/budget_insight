package com.moneyreserve.repository;

import com.moneyreserve.domain.BiBiller;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiBillerRepository extends JpaRepository<BiBiller, UUID> {

  List<BiBiller> findAllByIsActiveTrueOrderBySortOrderAsc();

  List<BiBiller> findAllByOrderBySortOrderAsc();

  Optional<BiBiller> findByCode(String code);
}
