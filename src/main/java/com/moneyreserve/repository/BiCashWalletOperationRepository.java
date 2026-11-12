package com.moneyreserve.repository;

import com.moneyreserve.domain.BiCashWalletOperation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiCashWalletOperationRepository extends JpaRepository<BiCashWalletOperation, UUID> {

  Optional<BiCashWalletOperation> findByUserIdAndClientRef(UUID userId, String clientRef);

  List<BiCashWalletOperation> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

  long deleteByUserId(UUID userId);
}
