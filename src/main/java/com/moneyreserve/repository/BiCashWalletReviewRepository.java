package com.moneyreserve.repository;

import com.moneyreserve.domain.BiCashWalletReview;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiCashWalletReviewRepository extends JpaRepository<BiCashWalletReview, UUID> {

  Optional<BiCashWalletReview> findByEntryId(UUID entryId);

  List<BiCashWalletReview> findAllByWalletIdAndResolvedAtIsNullOrderByDueAtAsc(UUID walletId);

  List<BiCashWalletReview> findAllByUserIdOrderByDueAtAsc(UUID userId);

  long deleteByUserId(UUID userId);
}
