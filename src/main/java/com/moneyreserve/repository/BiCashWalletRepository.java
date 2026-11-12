package com.moneyreserve.repository;

import com.moneyreserve.domain.BiCashWallet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiCashWalletRepository extends JpaRepository<BiCashWallet, UUID> {

  Optional<BiCashWallet> findByUserIdAndCurrency(UUID userId, String currency);

  /** The wallet row locked for the rest of the transaction: every balance change goes through it. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT w FROM BiCashWallet w WHERE w.userId = :userId AND w.currency = :currency")
  Optional<BiCashWallet> lockByUserIdAndCurrency(
      @Param("userId") UUID userId, @Param("currency") String currency);

  long deleteByUserId(UUID userId);
}
