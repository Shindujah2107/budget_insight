package com.moneyreserve.repository;

import com.moneyreserve.domain.ComplianceEventType;
import com.moneyreserve.domain.BiComplianceEvent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Append-only. No update or delete method is exposed here by design. */
public interface BiComplianceEventRepository extends JpaRepository<BiComplianceEvent, UUID> {

  long countByEventTypeAndOccurredAtAfter(ComplianceEventType eventType, Instant after);

  /**
   * Which of these client references have already been reported.
   *
   * <p>A read, not a write, so it does not breach the append-only rule above. One query for the
   * whole batch rather than one per event, and — more to the point — it means a replayed queue is
   * answered without attempting a single insert. An insert that violates
   * {@code ux_bi_ce_client_ref} does not fail politely: it poisons the Hibernate session and
   * takes every good row in the batch with it.
   */
  @Query(
      "select e.clientRefHash from BiComplianceEvent e "
          + "where e.clientRefHash in :hashes")
  List<String> findExistingClientRefHashes(
      @Param("hashes") Collection<String> hashes);
}
