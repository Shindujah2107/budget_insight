package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiTransactionAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiTransactionAuditRepository extends JpaRepository<BiTransactionAudit, UUID> {

  List<BiTransactionAudit> findAllByTransactionIdOrderByCreatedAtDesc(UUID transactionId);
}
