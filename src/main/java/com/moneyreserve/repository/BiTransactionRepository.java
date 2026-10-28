package com.moneyreserve.repository;

import com.moneyreserve.domain.BiTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Every method here is scoped by user. There is deliberately no finder that takes an id alone —
 * resolving a transaction by id without the owner is how cross-customer access defects happen.
 *
 * <p>Every aggregate applies the same three filters: DEBIT only, inside the period, not soft
 * deleted. They are repeated in each query rather than factored out because a spend total that
 * quietly included credits, or a deleted row, would be wrong in a way nobody notices until a
 * customer adds up their own receipts.
 */
public interface BiTransactionRepository extends JpaRepository<BiTransaction, UUID> {

  Optional<BiTransaction> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

  boolean existsByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  Optional<BiTransaction> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  /**
   * Every transaction in this set of keys, for one owner, in a single query.
   *
   * <p>Ingest asks this once per batch rather than once per message. Soft-deleted rows are
   * deliberately included: a deleted transaction still holds its half of
   * {@code ux_bi_txn_user_idem}, so a replayed message has to be reported as a duplicate rather
   * than inserted again.
   */
  List<BiTransaction> findAllByUserIdAndIdempotencyKeyIn(UUID userId, Set<String> idempotencyKeys);

  List<BiTransaction> findAllByUserIdAndTxnDateBetweenAndDeletedAtIsNullOrderByTxnDateDesc(
      UUID userId, LocalDate from, LocalDate to);

  /** Amount spent in a period. Returns null when the customer has no spend yet. */
  @Query(
      """
      SELECT SUM(t.amount) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
      """)
  BigDecimal sumSpendBetween(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * Category totals for the breakdown: {@code [categoryId (UUID), total (BigDecimal), count
   * (Long)]}, ranked by spend.
   *
   * <p>Categories with no spend in the period do not appear at all. The screen ranks by amount and
   * a zero row would sit at the bottom saying nothing, so it is excluded here rather than filtered
   * in Java.
   */
  @Query(
      """
      SELECT t.categoryId, SUM(t.amount), COUNT(t)
      FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
      GROUP BY t.categoryId
      ORDER BY SUM(t.amount) DESC
      """)
  List<Object[]> aggregateSpendByCategoryBetween(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * Daily spend for the activity chart, grouped by the instrument the money moved on (BI-16).
   *
   * <p><b>The join is an inner join on purpose.</b> It is what excludes spend that belongs to no
   * instrument — cash, manual entries, and every ingested card transaction while
   * {@code masked-card-to-backend-enabled} is off — from the two series. That exclusion is the
   * chart's definition rather than an accident, so it belongs in the query where it is visible,
   * not in a filter further up. {@code sumUnattributedSpendBetween} below reports what this leaves
   * out, so the client can account for the difference instead of appearing to lose money.
   *
   * <p>Returns one row per (date, instrument type) that has spend; the service densifies to seven
   * points per series. {@code Object[]} of (LocalDate, InstrumentType, BigDecimal), matching the
   * shape the breakdown aggregate already uses.
   *
   * <p><b>There are two of these rather than one with an optional account parameter.</b> The
   * obvious single query carries {@code (:accountId IS NULL OR t.accountId = :accountId)}, and
   * PostgreSQL cannot infer a type for a bare parameter inside {@code IS NULL}: it refuses the
   * statement with "could not determine data type of parameter". That failure is invisible to a
   * unit test, because a mocked repository never hands the JPQL to Hibernate -- it would have
   * surfaced the first time somebody opened the chart without a filter, which is the default. Two
   * queries also let the planner pick the right index for each: the unfiltered form uses
   * {@code ix_bi_txn_user_date}, the filtered form {@code ix_bi_txn_user_acct_date}.
   */
  @Query(
      """
      SELECT t.txnDate, a.instrumentType, SUM(t.amount)
      FROM BiTransaction t
      JOIN BiAccount a ON a.id = t.accountId
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND a.deletedAt IS NULL
      GROUP BY t.txnDate, a.instrumentType
      ORDER BY t.txnDate
      """)
  List<Object[]> aggregateDailySpendByInstrumentBetween(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** The same series, narrowed to one of the customer's accounts. See the note above on why. */
  @Query(
      """
      SELECT t.txnDate, a.instrumentType, SUM(t.amount)
      FROM BiTransaction t
      JOIN BiAccount a ON a.id = t.accountId
      WHERE t.userId = :userId
        AND t.accountId = :accountId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND a.deletedAt IS NULL
      GROUP BY t.txnDate, a.instrumentType
      ORDER BY t.txnDate
      """)
  List<Object[]> aggregateDailySpendByInstrumentForAccountBetween(
      @Param("userId") UUID userId,
      @Param("accountId") UUID accountId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Spend in the window that the chart's two series cannot show, because it is attached to no
   * account.
   *
   * <p>Reported so the customer is not left comparing a chart against a breakdown total and
   * finding money missing with no explanation.
   *
   * <p>Two populations, not one. A transaction with no account at all is the obvious case — cash,
   * manual entries, and every ingested card transaction while the masked tail is being dropped.
   * The second is a transaction whose account has since been soft-deleted: the series queries join
   * on {@code a.deletedAt IS NULL} and so exclude it, and without the EXISTS clause below it would
   * fall out of both halves and the two would no longer add up to the breakdown.
   */
  @Query(
      """
      SELECT SUM(t.amount) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND (t.accountId IS NULL
             OR NOT EXISTS (SELECT 1 FROM BiAccount a
                            WHERE a.id = t.accountId AND a.deletedAt IS NULL))
      """)
  BigDecimal sumUnattributedSpendBetween(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** The total behind one expanded category row. Must agree with the breakdown to the cent. */
  @Query(
      """
      SELECT SUM(t.amount) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.categoryId = :categoryId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
      """)
  BigDecimal sumSpendByCategoryBetween(
      @Param("userId") UUID userId,
      @Param("categoryId") UUID categoryId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * The transactions behind one expanded category row.
   *
   * <p>Ordering is fixed in the query, so callers pass an unsorted {@link Pageable}. Newest first,
   * with the timestamp breaking ties inside a day and the id breaking ties beyond that — without a
   * total order, two pages of the same list can show the same row twice.
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.categoryId = :categoryId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
      ORDER BY t.txnDate DESC, t.txnTimestamp DESC NULLS LAST, t.createdAt DESC, t.id DESC
      """)
  Page<BiTransaction> pageCategorySpend(
      @Param("userId") UUID userId,
      @Param("categoryId") UUID categoryId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      Pageable pageable);

  /**
   * Customers with any ledger activity since a date, for the aggregate refresh (BI-29).
   *
   * <p>Bounded by the look-back rather than by "has ever transacted". The window is already the
   * approved position on how much history this service analyses, so it is also the honest answer
   * to who it is currently doing any work for — and it keeps a nightly job from growing in
   * proportion to every account ever opened.
   *
   * <p>Soft-deleted rows do not count. A customer whose only transactions have been deleted has
   * nothing to aggregate, and warming a cache for an empty breakdown is work with no reader.
   */
  @Query(
      """
      SELECT DISTINCT t.userId FROM BiTransaction t
      WHERE t.txnDate >= :since
        AND t.deletedAt IS NULL
      """)
  List<UUID> findActiveUserIdsSince(@Param("since") LocalDate since);

  // ---------------------------------------------------------------------------------------------
  // Budget Insights admin. Counts only: no amount, merchant-to-customer link or user id leaves
  // these queries.
  // ---------------------------------------------------------------------------------------------

  /** Per current category: {@code [categoryId, transactions since from, all transactions]}. */
  @Query(
      """
      SELECT t.categoryId, SUM(CASE WHEN t.txnDate >= :from THEN 1 ELSE 0 END), COUNT(t)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL
      GROUP BY t.categoryId
      """)
  List<Object[]> countByCategorySince(@Param("from") LocalDate from);

  /** Every row that references a category through either foreign key, soft-deleted included. */
  @Query(
      "SELECT COUNT(t) FROM BiTransaction t WHERE t.categoryId = :id OR t.autoCategoryId = :id")
  long countReferencingCategory(@Param("id") UUID categoryId);

  /**
   * Per parser template: {@code [templateId, transactions since from, all transactions, when the
   * newest was stored]}.
   */
  @Query(
      """
      SELECT t.parserTemplateId, SUM(CASE WHEN t.txnDate >= :from THEN 1 ELSE 0 END), COUNT(t),
             MAX(t.createdAt)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL AND t.parserTemplateId IS NOT NULL
      GROUP BY t.parserTemplateId
      """)
  List<Object[]> countByTemplateSince(@Param("from") LocalDate from);


  /** Every merchant since a date, both directions: {@code [merchant, transactions]}. */
  @Query(
      """
      SELECT t.merchant, COUNT(t)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL AND t.txnDate >= :from AND t.merchant IS NOT NULL
      GROUP BY t.merchant
      """)
  List<Object[]> countByMerchantSince(@Param("from") LocalDate from);

  /** Debit transactions since a date (the denominator for merchant-rule reach). */
  @Query(
      """
      SELECT COUNT(t) FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate >= :from
      """)
  long countDebitsSince(@Param("from") LocalDate from);

  /** Debit merchants since a date: {@code [merchant, categoryId, transactions]}. */
  @Query(
      """
      SELECT t.merchant, t.categoryId, COUNT(t)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate >= :from
        AND t.merchant IS NOT NULL
      GROUP BY t.merchant, t.categoryId
      """)
  List<Object[]> countDebitsByMerchantAndCategorySince(@Param("from") LocalDate from);

  /**
   * Debits the engine left in OTHER since a date, by merchant: {@code [merchant, transactions,
   * distinct customers]}. Uses {@code autoCategoryId} (what the engine decided, not the customer's
   * correction) and leaves out messages whose type is decided by layer 0 (cash, fees), which are in
   * OTHER on purpose.
   */
  @Query(
      """
      SELECT t.merchant, COUNT(t), COUNT(DISTINCT t.userId)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate >= :from
        AND t.merchant IS NOT NULL
        AND t.autoCategoryId = :other
        AND (t.parserTemplateId IS NULL
             OR t.parserTemplateId NOT IN (
                 SELECT p.id FROM BiBankParserTemplate p WHERE p.messageType IN :excludedTypes))
      GROUP BY t.merchant
      """)
  List<Object[]> countUncategorisedDebitsByMerchantSince(
      @Param("from") LocalDate from,
      @Param("other") UUID other,
      @Param("excludedTypes") List<String> excludedTypes);

  /** Debit merchants since a date: {@code [merchant, distinct customers]} (k-anonymity input). */
  @Query(
      """
      SELECT t.merchant, COUNT(DISTINCT t.userId)
      FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate >= :from
        AND t.merchant IS NOT NULL
      GROUP BY t.merchant
      """)
  List<Object[]> countCustomersByDebitMerchantSince(@Param("from") LocalDate from);

  @Query(
      """
      SELECT COUNT(DISTINCT t.userId) FROM BiTransaction t
      WHERE t.deletedAt IS NULL AND t.txnDate BETWEEN :from AND :to
      """)
  long countCustomersBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** {@code [txnDate, source, transactions]} for the Overview's daily chart. */
  @Query(
      """
      SELECT t.txnDate, t.source, COUNT(t) FROM BiTransaction t
      WHERE t.deletedAt IS NULL AND t.txnDate BETWEEN :from AND :to
      GROUP BY t.txnDate, t.source
      """)
  List<Object[]> countDailyBySource(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** {@code [categoryId, transactions]} for the Overview's category distribution. */
  @Query(
      """
      SELECT t.categoryId, COUNT(t) FROM BiTransaction t
      WHERE t.deletedAt IS NULL AND t.txnDate BETWEEN :from AND :to
      GROUP BY t.categoryId
      """)
  List<Object[]> countByCategoryBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to);

  /**
   * {@code [bankCode, transactions]}, attributed through the parser template rather than the
   * account (HNB and BOC account alerts are stored with no account). Manual entries have no
   * template and are not counted.
   */
  @Query(
      """
      SELECT p.bankCode, COUNT(t) FROM BiTransaction t, BiBankParserTemplate p
      WHERE p.id = t.parserTemplateId
        AND t.deletedAt IS NULL AND t.txnDate BETWEEN :from AND :to
      GROUP BY p.bankCode
      """)
  List<Object[]> countByBankBetween(
      @Param("from") LocalDate from, @Param("to") LocalDate to);
}
