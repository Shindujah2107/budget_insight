package com.moneyreserve.repository;

import com.moneyreserve.domain.BiTransactionAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiTransactionAuditRepository extends JpaRepository<BiTransactionAudit, UUID> {

  List<BiTransactionAudit> findAllByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}
