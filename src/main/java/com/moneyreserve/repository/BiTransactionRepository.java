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
 *
 * <p>Spend totals also leave out {@code CASH_WITHDRAWAL}: cash taken out of an ATM is money moved
 * to the customer's pocket, not spent, and what they spend it on is added by hand. The rows stay
 * in the ledger list; only the totals skip them. So do confirmed own-money movements
 * ({@code movement}: cash to bank, bank to cash, balance corrections, V1.21.0), which are neither
 * spending nor income.
 */
public interface BiTransactionRepository extends JpaRepository<BiTransaction, UUID> {

  /**
   * Payments the app categorised by itself (never one a person chose), after {@code after} in id
   * order, for the admin "Re-sort old transactions" run (V1.31.0). Money moves are left out.
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND t.id > :after
        AND (t.isUserCategorised IS NULL OR t.isUserCategorised = FALSE)
        AND (t.categorySource IS NULL OR t.categorySource <> com.moneyreserve.domain.CategorySource.USER)
        AND t.movement IS NULL
        AND t.categoryId IS NOT NULL
      ORDER BY t.id
      """)
  List<BiTransaction> findAutoCategorisedAfter(
      @Param("after") UUID after, org.springframework.data.domain.Pageable page);

  /** Payments a person categorised (left alone by a re-sort). */
  @Query(
      """
      SELECT COUNT(t) FROM BiTransaction t
      WHERE t.deletedAt IS NULL
        AND (t.isUserCategorised = TRUE OR t.categorySource = com.moneyreserve.domain.CategorySource.USER)
      """)
  long countUserCategorised();

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
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND t.movement IS NULL
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
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND t.movement IS NULL
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
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND t.movement IS NULL
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
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND t.movement IS NULL
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
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND t.movement IS NULL
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
        AND t.movement IS NULL
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
        AND t.movement IS NULL
      ORDER BY t.txnDate DESC, t.txnTimestamp DESC NULLS LAST, t.createdAt DESC, t.id DESC
      """)
  Page<BiTransaction> pageCategorySpend(
      @Param("userId") UUID userId,
      @Param("categoryId") UUID categoryId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      Pageable pageable);

  /** A customer's live rows that name a merchant, for applying their merchant choice. */
  List<BiTransaction> findAllByUserIdAndMerchantIsNotNullAndDeletedAtIsNull(UUID userId);

  /**
   * The ledger list: every live transaction of one customer in a period, money out and money in,
   * optionally narrowed to one direction and one account. Same total order as {@link
   * #pageCategorySpend}, so pages never repeat or skip a row.
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND (:direction IS NULL OR t.direction = :direction)
        AND (:accountId IS NULL OR t.accountId = :accountId)
        AND (:source IS NULL OR t.categorySource = :source)
      ORDER BY t.txnDate DESC, t.txnTimestamp DESC NULLS LAST, t.createdAt DESC, t.id DESC
      """)
  Page<BiTransaction> pageLedger(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("direction") com.moneyreserve.domain.TxnDirection direction,
      @Param("accountId") UUID accountId,
      @Param("source") com.moneyreserve.domain.CategorySource source,
      Pageable pageable);

  /** {@code [sum, count]} of money out no rule matched (FALLBACK), same scope as the ledger. */
  @Query(
      """
      SELECT SUM(t.amount), COUNT(t)
      FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.categorySource = com.moneyreserve.domain.CategorySource.FALLBACK
        AND (:accountId IS NULL OR t.accountId = :accountId)
      """)
  List<Object[]> sumUncategorised(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("accountId") UUID accountId);

  /** Rows stored before V1.17.0 whose category reason is still unknown, oldest first. */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.categorySource IS NULL
      ORDER BY t.createdAt ASC, t.id ASC
      """)
  List<BiTransaction> findWithoutCategorySource(Pageable pageable);

  /** {@code [direction, sum, count]} for the same scope as {@link #pageLedger}, both directions. */
  @Query(
      """
      SELECT t.direction, SUM(t.amount), COUNT(t)
      FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND (:accountId IS NULL OR t.accountId = :accountId)
        AND t.movement IS NULL
        AND NOT (t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
                 AND EXISTS (SELECT 1 FROM BiCategory cw
                             WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL'))
      GROUP BY t.direction
      """)
  List<Object[]> sumLedgerByDirection(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("accountId") UUID accountId);

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

  // ---- Recorded payments and bank SMS paired up (V1.23.0) ---------------------------------

  List<BiTransaction> findAllByUserIdAndLinkedKeyIn(UUID userId, Set<String> linkedKeys);

  /**
   * Payments the customer recorded (a confirmed attempt or a bill marked paid) that no bank SMS
   * has been paired with yet, for one amount and date window, oldest first.
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.origin IS NOT NULL
        AND t.userBillId IS NULL
        AND t.linkedKey IS NULL
        AND t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.amount = :amount
        AND t.txnDate BETWEEN :from AND :to
      ORDER BY t.txnDate ASC, t.createdAt ASC
      """)
  List<BiTransaction> findUnpairedRecorded(
      @Param("userId") UUID userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * Bank SMS payments not yet paired with a recorded payment or a bill, for one amount and date
   * window, oldest first.
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.source <> com.moneyreserve.domain.TxnSource.MANUAL
        AND t.origin IS NULL
        AND t.linkedKey IS NULL
        AND t.userBillId IS NULL
        AND t.movement IS NULL
        AND t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.amount = :amount
        AND t.txnDate BETWEEN :from AND :to
      ORDER BY t.txnDate ASC, t.createdAt ASC
      """)
  List<BiTransaction> findUnpairedSms(
      @Param("userId") UUID userId,
      @Param("amount") java.math.BigDecimal amount,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  List<BiTransaction> findAllByUserIdAndUserBillIdAndDeletedAtIsNull(UUID userId, UUID userBillId);

  /**
   * Payments the customer recorded for a bill (marked paid, or a confirmed attempt for that bill)
   * still waiting for their bank SMS, in a date window, oldest first (V1.26.0).
   */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.origin IS NOT NULL
        AND t.userBillId IS NOT NULL
        AND t.linkedKey IS NULL
        AND t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
      ORDER BY t.txnDate ASC, t.createdAt ASC
      """)
  List<BiTransaction> findUnpairedBillPayments(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  /** Bank SMS payments not yet paired or linked to a bill, in a date window, any amount. */
  @Query(
      """
      SELECT t FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.source <> com.moneyreserve.domain.TxnSource.MANUAL
        AND t.origin IS NULL
        AND t.linkedKey IS NULL
        AND t.userBillId IS NULL
        AND t.movement IS NULL
        AND t.deletedAt IS NULL
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
      ORDER BY t.txnDate ASC, t.createdAt ASC
      """)
  List<BiTransaction> findUnpairedSmsBetween(
      @Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

  java.util.Optional<BiTransaction> findByIdAndUserId(UUID id, UUID userId);

  // ---- "What changed?" (V1.28.0): one set of spending rules for the comparison and both periods'
  // transaction lists, so a drilldown always adds up to the figure it was opened from. ----------

  /**
   * Recorded spending, as the breakdown counts it (money out, not deleted, not a movement between
   * the customer's own accounts or into/out of the cash wallet, not a cash withdrawal), in one
   * currency, optionally on chosen accounts only. Refunds (money in) are not netted, as in the
   * breakdown. Unconfirmed payment attempts are never transactions.
   */
  String COMPARABLE_SPEND =
      """
       t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND t.movement IS NULL
        AND t.currency = :currency
        AND NOT EXISTS (SELECT 1 FROM BiCategory cw
                        WHERE cw.id = t.categoryId AND cw.code = 'CASH_WITHDRAWAL')
        AND (:allAccounts = TRUE OR t.accountId IN :accountIds)
      """;

  /** Per category: {@code [categoryId, total, count]}. Every matching row, never a page. */
  @Query("SELECT t.categoryId, SUM(t.amount), COUNT(t) FROM BiTransaction t WHERE" + COMPARABLE_SPEND + " GROUP BY t.categoryId")
  List<Object[]> aggregateComparableSpend(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("currency") String currency,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds);

  /** One category's comparable spending: {@code [total, count]}. */
  @Query("SELECT SUM(t.amount), COUNT(t) FROM BiTransaction t WHERE t.categoryId = :categoryId AND" + COMPARABLE_SPEND)
  List<Object[]> sumComparableSpend(
      @Param("userId") UUID userId,
      @Param("categoryId") UUID categoryId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("currency") String currency,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds);

  /** One category's comparable transactions, in the breakdown list's total order. */
  @Query(
      "SELECT t FROM BiTransaction t WHERE t.categoryId = :categoryId AND"
          + COMPARABLE_SPEND
          + " ORDER BY t.txnDate DESC, t.txnTimestamp DESC NULLS LAST, t.createdAt DESC, t.id DESC")
  Page<BiTransaction> pageComparableSpend(
      @Param("userId") UUID userId,
      @Param("categoryId") UUID categoryId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("currency") String currency,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds,
      Pageable pageable);

  /** Spending rows in another currency, left out of the comparison (counted, never summed). */
  @Query(
      """
      SELECT COUNT(t) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.DEBIT
        AND t.txnDate BETWEEN :from AND :to
        AND t.deletedAt IS NULL
        AND t.movement IS NULL
        AND t.currency <> :currency
        AND (:allAccounts = TRUE OR t.accountId IN :accountIds)
      """)
  long countOtherCurrencySpend(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("currency") String currency,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds);

  /** The customer's earliest recorded transaction (either direction), on the chosen accounts. */
  @Query(
      """
      SELECT MIN(t.txnDate) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.deletedAt IS NULL
        AND (:allAccounts = TRUE OR t.accountId IN :accountIds)
      """)
  LocalDate earliestRecordedOn(
      @Param("userId") UUID userId,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds);

  // ---------------------------------------------------------------------------------
  // Ask MR (V1.29.0)
  // ---------------------------------------------------------------------------------

  /** Every comparable spending row in the range (all categories), oldest first. */
  @Query(
      "SELECT t FROM BiTransaction t WHERE"
          + COMPARABLE_SPEND
          + " ORDER BY t.txnDate ASC, t.txnTimestamp ASC NULLS LAST, t.createdAt ASC, t.id ASC")
  List<BiTransaction> listComparableSpend(
      @Param("userId") UUID userId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("currency") String currency,
      @Param("allAccounts") boolean allAccounts,
      @Param("accountIds") java.util.Collection<UUID> accountIds);

  /** Money in recorded since {@code from} (not moves between the customer's own accounts). */
  @Query(
      """
      SELECT COUNT(t) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.direction = com.moneyreserve.domain.TxnDirection.CREDIT
        AND t.txnDate >= :from
        AND t.deletedAt IS NULL
        AND t.movement IS NULL
      """)
  long countIncomeSince(@Param("userId") UUID userId, @Param("from") LocalDate from);

  /** The newest transaction that came from a bank SMS, if any. */
  @Query(
      """
      SELECT MAX(t.txnDate) FROM BiTransaction t
      WHERE t.userId = :userId
        AND t.deletedAt IS NULL
        AND t.source IN (com.moneyreserve.domain.TxnSource.SMS_ANDROID, com.moneyreserve.domain.TxnSource.SMS_IOS)
      """)
  LocalDate latestSmsRecordedOn(@Param("userId") UUID userId);
}
