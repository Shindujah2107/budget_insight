package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.AuditAction;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.BiTransactionAudit;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.CreateTransactionRequest;
import com.moneyreserve.dto.TransactionMutationResponse;
import com.moneyreserve.dto.UpdateTransactionRequest;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiTransactionAuditRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.InsightsMapper;
import com.moneyreserve.service.InsightsQueryService;
import com.moneyreserve.service.InsightsTransactionService;
import com.moneyreserve.service.BudgetAlertService;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Locale;
import java.util.List;
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

  /** Bills radar: re-checked once a payment commits. Optional so focused tests need not wire it. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.moneyreserve.service.BillService bills;

  /** The cash wallet: every change to a cash-paid row moves it, in this same transaction. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private CashWalletService cash;

  /** Recorded payments and bank SMS for the same payment are paired, never counted twice. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private PaymentPairing pairing;
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

    // A recorded payment (a confirmed attempt, a bill marked paid) that was paired with a bank SMS
    // the first time round: the retry gets that SMS row back.
    String origin = request.getOrigin();
    if (origin != null) {
      Optional<BiTransaction> paired =
          transactions.findAllByUserIdAndLinkedKeyIn(userId, java.util.Set.of(idempotencyKey)).stream()
              .findFirst();
      if (paired.isPresent()) {
        return paired(userId, paired.get(), from, to);
      }
      if (!"DEBIT".equals(request.getDirection()) || request.getPaymentSource() != null) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE);
      }
      // A bill the customer says they paid: the bank SMS already stored for it, when that is
      // certain (V1.26.0). Anything else: the bank SMS only through a verified bank link.
      Optional<BiTransaction> sms =
          pairing == null
              ? Optional.empty()
              : request.getUserBillId() != null
                  ? pairing
                      .smsForBill(
                          userId, request.getOriginBankCode(), request.getUserBillId(), request.getAmount(), txnDate, null)
                      .filter(PaymentPairing.BillMatch::linked)
                      .map(PaymentPairing.BillMatch::sms)
                  : pairing.smsFor(
                      userId, request.getOriginBankCode(), null, request.getAmount(), txnDate);
      if (sms.isPresent()) {
        return pairWithStoredSms(caller, sms.get(), request, idempotencyKey, from, to);
      }
    }

    // What the engine would have said, kept whether or not the customer overrode it.
    CategorisationService.Decision decision =
        categorisation.decide(
            userId,
            new CategorisationInput(request.getMerchant(), null, direction(request.getDirection())));
    BiCategory auto = decision.category();
    boolean chosen = request.getCategoryId() != null;
    BiCategory category = chosen ? queries.requireCategory(request.getCategoryId()) : auto;

    BiAccount account = resolveAccount(userId, request.getAccountId());
    String currency =
        request.getCurrency() == null || request.getCurrency().isBlank()
            ? properties.getCurrency()
            : request.getCurrency();
    // Paid from the cash wallet: asked for, or cash (no account) while the wallet is active, so an
    // older app cannot spend cash behind the wallet's back. Cash taken out at an ATM is a movement
    // into the wallet, never an expense paid from it.
    String paymentSource = null;
    if (cash != null
        && origin == null
        && cash.paysFromWallet(
            userId, currency, request.getPaymentSource(), account == null ? null : account.getId())) {
      if (BiCategory.CASH_WITHDRAWAL_CODE.equals(category.getCode())) {
        if (CashWalletService.CASH_WALLET.equals(request.getPaymentSource())) {
          cash.requireSpendableCategory(category);
        }
      } else {
        paymentSource = CashWalletService.CASH_WALLET;
      }
    }

    BiTransaction txn =
        BiTransaction.builder()
            .userId(userId)
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
            .categorySource(chosen ? CategorySource.USER : decision.source())
            .source(TxnSource.MANUAL)
            .idempotencyKey(idempotencyKey)
            .paymentSource(paymentSource)
            .origin(origin)
            .originBankCode(origin == null ? null : request.getOriginBankCode())
            .userBillId(request.getUserBillId())
            .build();

    BiTransaction saved = transactions.save(txn);
    if (cash != null) {
      // Refuses (and rolls this whole save back) when the wallet doesn't hold enough.
      cash.sync(saved, true);
    }
    audit(saved, AuditAction.CREATE, null, category.getId(), userId);
    invalidate(userId, txnDate);
    // In this transaction, so the evaluation sees the row just written (BI-25).
    budgetAlerts.evaluate(caller, txnDate);
    if (bills != null) {
      bills.refreshAfterCommit(userId);
    }

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

  /**
   * Marks (or unmarks) a transaction as money moved between the customer's own accounts. Only a
   * plain bank transaction can be marked: a cash-wallet entry, an ATM withdrawal and a row that
   * already records another movement (bank to cash, a balance correction) keep their meaning.
   */
  private void applyOwnAccount(BiTransaction txn, boolean own) {
    String current = txn.getMovement();
    if (!own) {
      if (CashWalletService.OWN_ACCOUNT.equals(current)) {
        txn.setMovement(null);
      } else if (current != null) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE);
      }
      return;
    }
    if (CashWalletService.OWN_ACCOUNT.equals(current)) {
      return;
    }
    boolean withdrawal =
        txn.getCategoryId() != null
            && BiCategory.CASH_WITHDRAWAL_CODE.equals(queries.requireCategory(txn.getCategoryId()).getCode());
    if (current != null
        || CashWalletService.CASH_WALLET.equals(txn.getPaymentSource())
        || withdrawal) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE);
    }
    txn.setMovement(CashWalletService.OWN_ACCOUNT);
  }

  /**
   * The customer recorded a payment the bank had already sent an SMS for: the SMS row is that
   * payment. It takes the customer's category and bill, and the recorded payment's key, so a retry
   * is a duplicate; nothing new is counted.
   */
  private TransactionMutationResponse pairWithStoredSms(
      Caller caller,
      BiTransaction sms,
      CreateTransactionRequest request,
      String idempotencyKey,
      LocalDate from,
      LocalDate to) {
    UUID userId = caller.userId();
    UUID before = sms.getCategoryId();
    sms.setLinkedKey(idempotencyKey);
    if (request.getUserBillId() != null) {
      sms.setUserBillId(request.getUserBillId());
    }
    if (request.getCategoryId() != null && !request.getCategoryId().equals(before)) {
      sms.setCategoryId(queries.requireCategory(request.getCategoryId()).getId());
      sms.setIsUserCategorised(Boolean.TRUE);
      sms.setCategorySource(CategorySource.USER);
    }
    BiTransaction saved = transactions.save(sms);
    if (!java.util.Objects.equals(before, saved.getCategoryId())) {
      audit(saved, AuditAction.RECATEGORISE, before, saved.getCategoryId(), userId);
    }
    invalidate(userId, saved.getTxnDate());
    budgetAlerts.evaluate(caller, saved.getTxnDate());
    if (bills != null) {
      bills.refreshAfterCommit(userId);
    }
    log.info(
        "Insights recorded payment paired with a stored bank SMS txnId={} userId={}",
        saved.getId(),
        userId);
    return paired(userId, saved, from, to);
  }

  /** The bank SMS row a recorded payment turned out to be: flagged as a duplicate, not a new row. */
  private TransactionMutationResponse paired(UUID userId, BiTransaction txn, LocalDate from, LocalDate to) {
    transactions.flush();
    return TransactionMutationResponse.builder()
        .transaction(mapper.toDto(txn, accountOf(userId, txn.getAccountId())))
        .breakdown(queries.breakdown(userId, periodFor(from, to, txn.getTxnDate())))
        .duplicate(Boolean.TRUE)
        .build();
  }

  private static String merchantKey(String merchant) {
    String key = com.moneyreserve.service.calc.MerchantRuleMatcher.normalise(merchant);
    return key == null || key.length() <= 128 ? key : key.substring(0, 128);
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

    boolean sourceEdit = request.getPaymentSource() != null || request.getAccountId() != null;
    if (sourceEdit && !manual) {
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
      txn.setCategorySource(CategorySource.USER);
      recategorised = true;
    }
    // "Do this for every transaction from this merchant": remember the choice for future alerts and
    // move this customer's other rows from the same merchant. Never another customer's rows, and
    // never a global rule.
    List<BiTransaction> sameMerchant = List.of();
    if (Boolean.TRUE.equals(request.getApplyToMerchant())
        && txn.getMerchant() != null
        && txn.getCategoryId() != null) {
      String key = categorisation.remember(userId, txn.getMerchant(), txn.getCategoryId());
      if (key != null) {
        sameMerchant =
            transactions.findAllByUserIdAndMerchantIsNotNullAndDeletedAtIsNull(userId).stream()
                .filter(t -> !t.getId().equals(txn.getId()))
                .filter(t -> key.equals(merchantKey(t.getMerchant())))
                .filter(t -> !Objects.equals(t.getCategoryId(), txn.getCategoryId()))
                .toList();
      }
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
    if (sourceEdit) {
      if (CashWalletService.CASH_WALLET.equals(request.getPaymentSource())) {
        if (txn.getMovement() != null) {
          throw new BudgetInsightsException(ErrorCode.INSIGHTS_TXN_NOT_EDITABLE);
        }
        if (cash == null || !cash.paysFromWallet(userId, txn.getCurrency(), request.getPaymentSource(), null)) {
          throw new BudgetInsightsException(ErrorCode.CASH_NOT_ACTIVE);
        }
        txn.setAccountId(null);
        txn.setPaymentSource(CashWalletService.CASH_WALLET);
      } else {
        BiAccount account = resolveAccount(userId, request.getAccountId());
        txn.setAccountId(account == null ? null : account.getId());
        txn.setPaymentSource(null);
      }
    }
    String previousMovement = txn.getMovement();
    if (request.getMovement() != null) {
      applyOwnAccount(txn, "OWN_ACCOUNT".equals(request.getMovement()));
    }
    boolean movementChanged = !Objects.equals(previousMovement, txn.getMovement());
    if (cash != null
        && (recategorised || sourceEdit)
        && CashWalletService.CASH_WALLET.equals(txn.getPaymentSource())) {
      cash.requireSpendableCategory(queries.requireCategory(txn.getCategoryId()));
    }

    BiTransaction saved = transactions.save(txn);
    if (cash != null) {
      cash.sync(saved, CashWalletService.CASH_WALLET.equals(saved.getPaymentSource()));
    }

    if (recategorised) {
      audit(saved, AuditAction.RECATEGORISE, previousCategoryId, saved.getCategoryId(), userId);
    }
    java.util.Set<LocalDate> touched = new java.util.HashSet<>();
    for (BiTransaction other : sameMerchant) {
      UUID before = other.getCategoryId();
      other.setCategoryId(saved.getCategoryId());
      other.setIsUserCategorised(Boolean.TRUE);
      other.setCategorySource(CategorySource.USER);
      transactions.save(other);
      if (cash != null) {
        cash.sync(other, false);
      }
      audit(other, AuditAction.RECATEGORISE, before, other.getCategoryId(), userId);
      touched.add(other.getTxnDate().withDayOfMonth(1));
    }
    for (LocalDate month : touched) {
      invalidate(userId, month);
      budgetAlerts.evaluate(caller, month);
    }
    if (!sameMerchant.isEmpty()) {
      log.info(
          "Insights merchant choice applied txnId={} userId={} otherRows={}",
          saved.getId(),
          userId,
          sameMerchant.size());
    }
    if (fieldEdit || movementChanged) {
      audit(saved, AuditAction.EDIT, previousCategoryId, previousCategoryId, userId);
    }
    if (movementChanged) {
      log.info(
          "Insights transaction marked txnId={} userId={} movement={}",
          saved.getId(),
          userId,
          saved.getMovement());
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
    if (bills != null) {
      if (fieldEdit && saved.getUserBillId() != null) {
        bills.paymentEdited(userId, saved); // the bill cycle it settled shows the corrected figures
      }
      bills.refreshAfterCommit(caller.userId());
    }
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
    if (cash != null) {
      cash.sync(saved, false);
    }

    audit(saved, AuditAction.DELETE, saved.getCategoryId(), null, userId);
    invalidate(userId, saved.getTxnDate());
    // No alert evaluation here. A soft delete only ever reduces spend, so it cannot cross a
    // threshold, and an alert already raised stays raised: it records that the customer was told
    // something true at the time, and deleting the transaction does not un-tell them.
    // Bills do change: a deleted payment can end or shift a recurring bill, and the bill cycle it
    // settled can be answered again (V1.26.0).
    if (bills != null) {
      bills.paymentRemoved(userId, saved.getId());
      bills.refreshAfterCommit(userId);
    }

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
