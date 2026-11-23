package com.moneyreserve.repository;

import com.moneyreserve.domain.BiRecategoriseMove;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiRecategoriseMoveRepository extends JpaRepository<BiRecategoriseMove, BiRecategoriseMove.Key> {
  List<BiRecategoriseMove> findAllByRunId(UUID runId);
}
