package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiConfigChangeset;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** The single-row changeset revision counter. */
public interface BiConfigChangesetRepository extends JpaRepository<BiConfigChangeset, Short> {

  /** The counter row, locked until the surrounding transaction ends. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM BiConfigChangeset c WHERE c.id = 1")
  Optional<BiConfigChangeset> lockCounter();
}
