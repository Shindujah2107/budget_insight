package com.moneyreserve.repository;

import com.moneyreserve.domain.BiRecategoriseRun;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiRecategoriseRunRepository extends JpaRepository<BiRecategoriseRun, UUID> {
  List<BiRecategoriseRun> findTop20ByOrderByCreatedAtDesc();
}
