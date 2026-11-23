package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAskMrWorth;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiAskMrWorthRepository extends JpaRepository<BiAskMrWorth, UUID> {

  /** Cards shown to this customer on or after {@code from}. */
  List<BiAskMrWorth> findByUserIdAndShownOnGreaterThanEqual(UUID userId, LocalDate from);

  Optional<BiAskMrWorth> findByIdAndUserId(UUID id, UUID userId);
}
