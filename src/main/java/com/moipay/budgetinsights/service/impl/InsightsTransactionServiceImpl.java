package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.AuditAction;
import com.moipay.budgetinsights.domain.BiAccount;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiTransaction;
import com.moipay.budgetinsights.domain.BiTransactionAudit;
import com.moipay.budgetinsights.domain.TxnDirection;
import com.moipay.budgetinsights.domain.TxnSource;
import com.moipay.budgetinsights.dto.CreateTransactionRequest;
import com.moipay.budgetinsights.dto.TransactionMutationResponse;
import com.moipay.budgetinsights.dto.UpdateTransactionRequest;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiInsightCacheRepository;
import com.moipay.budgetinsights.repository.BiTransactionAuditRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.InsightsMapper;
import com.moipay.budgetinsights.service.InsightsQueryService;
import com.moipay.budgetinsights.service.InsightsTransactionService;
import com.moipay.budgetinsights.service.BudgetAlertService;
import com.moipay.budgetinsights.service.CategorisationInput;
import com.moipay.budgetinsights.service.CategorisationService;
import com.moipay.budgetinsights.service.calc.InsightsPeriod;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsTransactionServiceImpl implements InsightsTransactionService {

  private final InsightsProperties properties;
  private final InsightsMapper mapper;
  private final InsightsQueryService queries;
  private final CategorisationService categorisation;
  private final BudgetAlertService budgetAlerts;
  private final BiTransactionRepository transactions;
  private final BiTransactionAuditRepository audits;
  private final BiAccountRepository accounts;
  private final BiInsightCacheRepository caches;

  // ---------------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------------

  /**
   * Adds a transaction the customer entered by hand, typically cash.
   *
   * <p>A repeated request carrying the same {@code clientRef} returns the transaction that already
   * exists, flagged as a duplicate, rather than creating a second one. A phone that loses signal
   * between the write and the response will retry, and a customer who sees their grocery run twice
   * stops trusting the total.
   */
  @Override
  @Transactional
  public TransactionMutationResponse create(
      Caller caller, CreateTransactionRequest request, LocalDate from, LocalDate to) {

    UUID userId = caller.userId();
    LocalDate txnDate = requireUsableDate(request.getTxnDate());
    String idempotencyKey = idempotencyKey(request.getClientRef());

    Optional<BiTransaction> existing =
        transactions.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    if (existing.isPresent()) {
      BiTransaction found = existing.get();
      log.info(
          "Insights manual transaction retry ignored txnId={} userId={} (duplicate clientRef)",
          found.getId(),
          userId);
      return TransactionMutationResponse.builder()
          .transaction(mapper.toDto(found, accountOf(userId, found.getAccountId())))
          .breakdown(queries.breakdown(userId, periodFor(from, to, found.getTxnDate())))
          .duplicate(Boolean.TRUE)
          .deleted(found.isDeleted() ? Boolean.TRUE : null)
          .build();
    }

    // What the engine would have said, kept whether or not the customer overrode it.
    BiCategory auto =
        categorisation.categorise(
            new CategorisationInput(request.getMerchant(), null, direction(request.getDirection())));
    boolean chosen = request.getCategoryId() != null;
    BiCategory category = chosen ? queries.requireCategory(request.getCategoryId()) : auto;

    BiAccount account = resolveAccount(userId, request.getAccountId());

    BiTransaction txn =
        BiTransaction.builder()
            .userId(userId)
            .tenantId(caller.tenantId())
            .accountId(account == null ? null : account.getId())
            .amount(request.getAmount())
            .currency(
                request.getCurrency() == null || request.getCurrency().isBlank()
                    ? properties.getCurrency()
                    : request.getCurrency())
            .direction(direction(request.getDirection()))
            .merchant(trimToNull(request.getMerchant()))
            .description(trimToNull(request.getDescription()))
            .txnDate(txnDate)
            .txnTimestamp(OffsetDateTime.now())
            .categoryId(category.getId())
            .autoCategoryId(auto.getId())
            .isUserCategorised(chosen)
            .source(TxnSource.MANUAL)
            .idempotencyKey(idempotencyKey)
            .build();

    BiTransaction saved = transactions.save(txn);
    audit(saved, AuditAction.CREATE, null, category.getId(), userId);
    invalidate(userId, txnDate);
    // In this transaction, so the evaluation sees the row just written (BI-25).
    budgetAlerts.evaluate(caller, txnDate);

    // Ids, codes and counts only. No amount, merchant or note reaches the log: the whole point of
    // the schema's shape is that this feature's personal financial detail lives in one place, and
    // an application log that quietly mirrors it would defeat that.
    log.info(
        "Insights manual transaction created txnId={} userId={} categoryCode={} chosenByUser={}",
        saved.getId(),
        userId,
        category.getCode(),
        chosen);

    return respond(userId, saved, from, to, null, false);
  }

  // ---------------------------------------------------------------------------------
  // Update
  // ---------------------------------------------------------------------------------

  /**
   * Corrects a transaction.
   *
   * <p>The category may be corrected on any transaction, including an ingested one — a wrong
   * category the customer cannot fix would make the engine's mistakes permanent, on the one screen
   * where they are most visible. Every other field is editable only on a manual entry: an ingested
   * row records what the bank said, and an edited amount would agree with neither the bank nor the
   * customer.
   */
  @Override
  @Transactional
  public TransactionMutationResponse update(
      Caller caller, UUID id, UpdateTransactionRequest request, LocalDate from, LocalDate to) {

    UUID userId = caller.userId();
    BiTransaction txn =
        transactions
            .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_FOUND));

    boolean manual = TxnSource.MANUAL.equals(txn.getSource());
    boolean fieldEdit =
        request.getAmount() != null
            || request.getMerchant() != null
            || request.getTxnDate() != null
            || request.getDescription() != null
            || Boolean.TRUE.equals(request.getClearDescription());

    if (fieldEdit && !manual) {
      log.info(
          "Insights edit refused on an ingested transaction txnId={} userId={} source={}",
          txn.getId(),
          userId,
          txn.getSource());
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE);
    }

    LocalDate previousDate = txn.getTxnDate();
    UUID previousCategoryId = txn.getCategoryId();
    boolean recategorised = false;

    if (request.getCategoryId() != null
        && !Objects.equals(request.getCategoryId(), previousCategoryId)) {
      BiCategory category = queries.requireCategory(request.getCategoryId());
      txn.setCategoryId(category.getId());
      // The latch. From here on the categorisation engine is a spectator on this row.
      txn.setIsUserCategorised(Boolean.TRUE);
      recategorised = true;
    }

    if (request.getAmount() != null) {
      txn.setAmount(request.getAmount());
    }
    if (request.getMerchant() != null) {
      txn.setMerchant(trimToNull(request.getMerchant()));
    }
    if (Boolean.TRUE.equals(request.getClearDescription())) {
      txn.setDescription(null);
    } else if (request.getDescription() != null) {
      txn.setDescription(trimToNull(request.getDescription()));
    }
    if (request.getTxnDate() != null) {
      txn.setTxnDate(requireUsableDate(request.getTxnDate()));
    }

    BiTransaction saved = transactions.save(txn);

    if (recategorised) {
      audit(saved, AuditAction.RECATEGORISE, previousCategoryId, saved.getCategoryId(), userId);
    }
    if (fieldEdit) {
      audit(saved, AuditAction.EDIT, previousCategoryId, previousCategoryId, userId);
    }

    // A moved date takes spend out of one period and puts it into another, so both are stale.
    // A date that did not move is the common case -- a re-categorisation -- and invalidating it a
    // second time is an identical UPDATE against bi_insight_cache that can clear nothing the first
    // one did not, plus a duplicate log line on the one statement operators read to answer "did
    // invalidation actually run".
    invalidate(userId, previousDate);
    if (!Objects.equals(previousDate, saved.getTxnDate())) {
      invalidate(userId, saved.getTxnDate());
    }

    // An edit can cross a threshold three ways: a larger amount, a move into a category that was
    // already near its cap, or a date moved into a different month. Evaluate the period the row
    // landed in, and the one it left if that is a different month -- leaving is the one case that
    // cannot raise anything, but evaluating it is cheap and a month that still has a crossing in
    // it should not go unreported because the trigger moved away.
    budgetAlerts.evaluate(caller, saved.getTxnDate());
    if (previousDate != null
        && !YearMonth.from(previousDate).equals(YearMonth.from(saved.getTxnDate()))) {
      budgetAlerts.evaluate(caller, previousDate);
    }

    if (recategorised) {
      // The single most common action on this screen, and the one that sets the latch. Logged at
      // INFO because the gap between what the engine chose and what the customer chose is how the
      // merchant map gets improved.
      log.info(
          "Insights transaction recategorised txnId={} userId={} from={} to={}",
          saved.getId(),
          userId,
          previousCategoryId,
          saved.getCategoryId());
    }
    if (fieldEdit) {
      log.info(
          "Insights manual transaction edited txnId={} userId={} dateMoved={}",
          saved.getId(),
          userId,
          !previousDate.equals(saved.getTxnDate()));
    }

    return respond(userId, saved, from, to, recategorised ? previousCategoryId : null, false);
  }

  // ---------------------------------------------------------------------------------
  // Delete
  // ---------------------------------------------------------------------------------

  /**
   * Removes a transaction from every figure on the screen, without destroying it.
   *
   * <p>Soft delete, because the audit trail of a removed transaction is the only way to answer a
   * customer who says a number changed and they do not know why.
   */
  @Override
  @Transactional
  public TransactionMutationResponse delete(Caller caller, UUID id, LocalDate from, LocalDate to) {
    UUID userId = caller.userId();
    BiTransaction txn =
        transactions
            .findByIdAndUserIdAndDeletedAtIsNull(id, userId)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_FOUND));

    txn.setDeletedAt(OffsetDateTime.now());
    BiTransaction saved = transactions.save(txn);

    audit(saved, AuditAction.DELETE, saved.getCategoryId(), null, userId);
    invalidate(userId, saved.getTxnDate());
    // No alert evaluation here. A soft delete only ever reduces spend, so it cannot cross a
    // threshold, and an alert already raised stays raised: it records that the customer was told
    // something true at the time, and deleting the transaction does not un-tell them.

    log.info(
        "Insights transaction soft deleted txnId={} userId={} source={}",
        saved.getId(),
        userId,
        saved.getSource());

    return respond(userId, saved, from, to, null, true);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * Builds the response, recomputing the breakdown inside the same transaction as the write.
   *
   * <p>The explicit flush is what makes that recomputation see the row just written. Relying on
   * Hibernate's automatic flush before a query would work today and would be a silent correctness
   * bug the first time somebody changes a flush mode.
   */
  private TransactionMutationResponse respond(
      UUID userId,
      BiTransaction txn,
      LocalDate from,
      LocalDate to,
      UUID previousCategoryId,
      boolean deleted) {

    transactions.flush();
    return TransactionMutationResponse.builder()
        .transaction(mapper.toDto(txn, accountOf(userId, txn.getAccountId())))
        .breakdown(queries.breakdown(userId, periodFor(from, to, txn.getTxnDate())))
        .previousCategoryId(previousCategoryId)
        .deleted(deleted ? Boolean.TRUE : null)
        .build();
  }

  /**
   * The period the recomputed breakdown covers.
   *
   * <p>The client's currently displayed range wins when it sends one. Otherwise the month of the
   * transaction is used, which is the month the customer was almost certainly looking at.
   */
  private InsightsPeriod periodFor(LocalDate from, LocalDate to, LocalDate txnDate) {
    if (from == null && to == null) {
      return queries.period(txnDate, null);
    }
    return queries.period(from, to);
  }

  private void audit(
      BiTransaction txn, AuditAction action, UUID oldCategoryId, UUID newCategoryId, UUID userId) {
    audits.save(
        BiTransactionAudit.builder()
            .transactionId(txn.getId())
            .action(action)
            .oldCategoryId(oldCategoryId)
            .newCategoryId(newCategoryId)
            .actor(actor(userId))
            .build());
  }

  /**
   * Who made the change. The customer's own identifier, not their name or email — this row lives
   * beside financial data and has no reason to carry anything readable.
   */
  private String actor(UUID userId) {
    return "USER:" + userId;
  }

  private void invalidate(UUID userId, LocalDate date) {
    if (date == null) {
      return;
    }
    int cleared = caches.invalidateCovering(userId, date);
    // When a customer reports a total that will not move, this line is what says whether
    // invalidation actually ran. One line per write, which is fine at R1 volumes; R2 ingest should
    // report a single count per batch rather than inheriting this.
    log.info(
        "Insights aggregate cache invalidated userId={} coveringDate={} rowsCleared={}",
        userId,
        date,
        cleared);
  }

  private BiAccount resolveAccount(UUID userId, UUID accountId) {
    if (accountId == null) {
      return null; // Cash. Belongs to no instrument, by design.
    }
    return accounts
        .findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_ACCOUNT_NOT_FOUND));
  }

  /** The account behind a transaction, for its display label. Null for cash. */
  private BiAccount accountOf(UUID userId, UUID accountId) {
    if (accountId == null) {
      return null;
    }
    return accounts.findByIdAndUserIdAndDeletedAtIsNull(accountId, userId).orElse(null);
  }

  /**
   * Rejects a date outside the window Budget Insights is approved to analyse, and any date in the
   * future.
   *
   * <p>A future-dated entry is not a budget; it is a plan, and letting one into the ledger makes
   * "spent so far this month" mean something other than what it says.
   */
  private LocalDate requireUsableDate(LocalDate date) {
    // In the configured zone, like every other date this service treats as "today". In UTC, a
    // customer entering a transaction late in the evening in Colombo is entering tomorrow's date,
    // and would be refused for a transaction they have just made.
    LocalDate today = LocalDate.now(properties.zoneId());
    if (date == null || date.isAfter(today)) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BUDGET_INVALID_PERIOD);
    }
    LocalDate earliest = today.minusMonths(properties.getLookbackMonths()).withDayOfMonth(1);
    if (date.isBefore(earliest)) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_LOOKBACK_EXCEEDED);
    }
    return date;
  }

  private TxnDirection direction(String raw) {
    if (raw == null || raw.isBlank()) {
      return TxnDirection.DEBIT;
    }
    return TxnDirection.valueOf(raw.trim().toUpperCase(Locale.ROOT));
  }

  /** Manual entries get a namespaced key so they can never collide with an ingested message's. */
  private String idempotencyKey(String clientRef) {
    String ref =
        (clientRef == null || clientRef.isBlank()) ? UUID.randomUUID().toString() : clientRef.trim();
    return "MANUAL:" + ref;
  }

  private String trimToNull(String raw) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim();
    return v.isEmpty() ? null : v;
  }
}
