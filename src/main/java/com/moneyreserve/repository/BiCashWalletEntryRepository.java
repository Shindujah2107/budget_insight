package com.moneyreserve.repository;

import com.moneyreserve.domain.BiCashWalletEntry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiCashWalletEntryRepository extends JpaRepository<BiCashWalletEntry, UUID> {

  /** The transaction's live effect on the wallet, if it has one. */
  @Query(
      "SELECT e FROM BiCashWalletEntry e WHERE e.transactionId = :txnId AND e.reversed = false"
          + " AND e.reversesEntryId IS NULL")
  Optional<BiCashWalletEntry> findLiveByTransactionId(@Param("txnId") UUID transactionId);

  @Query("SELECT COALESCE(SUM(e.amount), 0) FROM BiCashWalletEntry e WHERE e.walletId = :walletId")
  BigDecimal sumByWalletId(@Param("walletId") UUID walletId);

  /** Live entries that carry a transaction, newest first: the wallet's activity. */
  @Query(
      "SELECT e FROM BiCashWalletEntry e WHERE e.walletId = :walletId AND e.transactionId IS NOT NULL"
          + " AND e.reversed = false AND e.reversesEntryId IS NULL ORDER BY e.createdAt DESC, e.id DESC")
  Page<BiCashWalletEntry> pageActivity(@Param("walletId") UUID walletId, Pageable pageable);

  List<BiCashWalletEntry> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

  /** Live withdrawal credits of a wallet, oldest first: what a bank reversal may answer. */
  @Query(
      "SELECT e FROM BiCashWalletEntry e WHERE e.walletId = :walletId AND e.kind = :kind"
          + " AND e.reversed = false AND e.reversesEntryId IS NULL ORDER BY e.createdAt ASC")
  List<BiCashWalletEntry> findLiveByKind(
      @Param("walletId") UUID walletId, @Param("kind") BiCashWalletEntry.Kind kind);

  /** Whether a withdrawal has already been answered by a live reversal entry. */
  @Query(
      "SELECT COUNT(e) > 0 FROM BiCashWalletEntry e WHERE e.linkedTransactionId = :txnId"
          + " AND e.reversed = false AND e.reversesEntryId IS NULL")
  boolean existsLiveLink(@Param("txnId") UUID transactionId);

  long deleteByUserId(UUID userId);
}
