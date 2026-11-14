package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiCashWallet;
import com.moneyreserve.domain.BiCashWalletEntry;
import com.moneyreserve.domain.BiCashWalletEntry.Kind;
import com.moneyreserve.domain.BiCashWalletOperation;
import com.moneyreserve.domain.BiCashWalletReview;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.CashWalletDto;
import com.moneyreserve.dto.CashWalletReconcileRequest;
import com.moneyreserve.dto.TransactionDto;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiCashWalletEntryRepository;
import com.moneyreserve.repository.BiCashWalletOperationRepository;
import com.moneyreserve.repository.BiCashWalletRepository;
import com.moneyreserve.repository.BiCashWalletReviewRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.BudgetAlertService;
import com.moneyreserve.service.InsightsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cash wallet (V1.21.0): one pooled balance per customer and currency, kept as a ledger of
 * entries.
 *
 * <p><b>One lock.</b> Every change to a wallet — a cash expense saved, edited or deleted, an ATM
 * withdrawal imported, a reconciliation — locks the wallet row first, in the same database
 * transaction as the transaction row it belongs to. A failed save rolls back the row, its wallet
 * entry, the balance and the revision together.
 *
 * <p><b>A transaction's effect is recomputed, not patched.</b> {@link #sync} works out what the
 * transaction should do to the wallet now (nothing once deleted, minus the amount for a cash
 * expense, plus it for a credited withdrawal, ...) and, if that differs from its live entry,
 * reverses the live entry and writes the new one. Edits, deletes, a payment-source change or a
 * recategorised withdrawal therefore apply exactly once, and a transaction never has two live
 * effects (also a unique index in PostgreSQL).
 *
 * <p><b>Activation is explicit.</b> Until the customer states the cash they hold, nothing is
 * credited. After it, an ATM withdrawal counts only if it reached the server after activation and
 * is dated on or after the activation day: history imported late (a first SMS import, a phone
 * catching up) never adds cash that was already in the opening amount or spent long ago.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashWalletService {

  public static final String CASH_WALLET = "CASH_WALLET";
  public static final String BANK_TO_CASH = "BANK_TO_CASH";
  public static final String CASH_TO_BANK = "CASH_TO_BANK";
  public static final String OWN_ACCOUNT = "OWN_ACCOUNT";
  public static final String BALANCE_CORRECTION = "BALANCE_CORRECTION";

  /** A credited withdrawal asks for a balance check this long after it. */
  static final Duration REVIEW_AFTER = Duration.ofDays(7);

  static final int ACTIVITY_SIZE = 20;
  private static final int MAX_PAGE = 50;
  private static final Pattern CLIENT_REF = Pattern.compile("^[A-Za-z0-9._:-]{8,96}$");

  private final BiCashWalletRepository wallets;
  private final BiCashWalletEntryRepository entries;
  private final BiCashWalletOperationRepository operations;
  private final BiCashWalletReviewRepository reviews;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiAccountRepository accounts;
  private final BiInsightCacheRepository caches;
  private final BudgetAlertService budgetAlerts;
  private final InsightsMapper mapper;
  private final InsightsProperties properties;
  private final jakarta.persistence.EntityManager entityManager;

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public CashWalletDto get(UUID userId, String currency) {
    String cur = currency(currency);
    return wallets
        .findByUserIdAndCurrency(userId, cur)
        .map(this::toDto)
        .orElseGet(() -> inactive(cur));
  }

  /** The wallet's activity, newest first, a page at a time. Never used to compute the balance. */
  @Transactional(readOnly = true)
  public List<TransactionDto> activity(UUID userId, String currency, int page, int size) {
    Optional<BiCashWallet> wallet = wallets.findByUserIdAndCurrency(userId, currency(currency));
    if (wallet.isEmpty()) {
      return List.of();
    }
    int pageSize = Math.max(1, Math.min(MAX_PAGE, size));
    return activityOf(wallet.get(), Math.max(0, page), pageSize);
  }

  // ---------------------------------------------------------------------------------------------
  // Transactions: called inside the transaction that wrote the row
  // ---------------------------------------------------------------------------------------------

  /**
   * Whether a new manual entry is paid from the cash wallet: asked for explicitly, or a cash entry
   * (no account) from a client that does not know about the wallet while the wallet is active, so
   * an older app cannot spend cash without the wallet noticing.
   */
  public boolean paysFromWallet(UUID userId, String currency, String requested, UUID accountId) {
    if (CASH_WALLET.equals(requested)) {
      if (accountId != null) {
        throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
      }
      if (!isActive(userId, currency)) {
        throw new BudgetInsightsException(ErrorCode.CASH_NOT_ACTIVE);
      }
      return true;
    }
    return accountId == null && isActive(userId, currency);
  }

  /** Cash taken out at an ATM is a movement into the wallet, never something paid from it. */
  public void requireSpendableCategory(BiCategory category) {
    if (category != null && BiCategory.CASH_WITHDRAWAL_CODE.equals(category.getCode())) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
  }

  /**
   * Brings the wallet in line with one transaction, under the wallet lock. {@code enforceFunds}:
   * refuse (and roll back the caller's whole save) if this change would take a cash expense
   * beyond what the wallet holds.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void sync(BiTransaction txn, boolean enforceFunds) {
    String cur = currency(txn.getCurrency());
    Optional<BiCashWallet> found = locked(txn.getUserId(), cur);
    if (found.isEmpty()) {
      if (CASH_WALLET.equals(txn.getPaymentSource()) && txn.getDeletedAt() == null) {
        throw new BudgetInsightsException(ErrorCode.CASH_NOT_ACTIVE);
      }
      return;
    }
    BiCashWallet wallet = found.get();
    if (CASH_WALLET.equals(txn.getPaymentSource()) && txn.getDeletedAt() == null && !wallet.isActive()) {
      throw new BudgetInsightsException(ErrorCode.CASH_NOT_ACTIVE);
    }

    Optional<BiCashWalletEntry> live = entries.findLiveByTransactionId(txn.getId());
    BigDecimal have = live.map(BiCashWalletEntry::getAmount).orElse(BigDecimal.ZERO);
    BigDecimal want = effect(txn, wallet);
    if (want.compareTo(have) == 0) {
      return;
    }
    BigDecimal next = wallet.getBalance().subtract(have).add(want);
    if (enforceFunds && next.signum() < 0 && next.compareTo(wallet.getBalance()) < 0) {
      log.info(
          "Cash wallet refused a payment txnId={} userId={} (insufficient)", txn.getId(), txn.getUserId());
      throw new BudgetInsightsException(ErrorCode.CASH_INSUFFICIENT);
    }

    long revision = wallet.getRevision() + 1;
    OffsetDateTime now = OffsetDateTime.now();
    String actor = "user:" + txn.getUserId();
    BigDecimal balance = wallet.getBalance();
    if (live.isPresent()) {
      BiCashWalletEntry old = live.get();
      old.setReversed(true);
      // Flushed now: Hibernate runs a flush's inserts before its updates, so without this the
      // new live entry below would be inserted while the old one is still live, and
      // ux_bi_cash_entry_live_txn (one live entry per transaction) refuses the edit.
      entries.saveAndFlush(old);
      balance = balance.subtract(old.getAmount());
      entries.save(
          entry(wallet, Kind.REVERSAL, old.getAmount().negate(), balance, revision, txn.getId(), now, actor)
              .reversesEntryId(old.getId())
              .build());
      reviews.findByEntryId(old.getId()).ifPresent(r -> {
        if (r.getResolvedAt() == null) {
          r.setResolvedAt(now);
          r.setResolvedRevision(revision);
          reviews.save(r);
        }
      });
    }
    if (want.signum() != 0) {
      balance = balance.add(want);
      BiCashWalletEntry applied =
          entries.save(entry(wallet, kindOf(txn), want, balance, revision, txn.getId(), now, actor).build());
      if (applied.getKind() == Kind.WITHDRAWAL) {
        reviews.save(
            BiCashWalletReview.builder()
                .id(UUID.randomUUID())
                .walletId(wallet.getId())
                .userId(wallet.getUserId())
                .entryId(applied.getId())
                .dueAt(now.plus(REVIEW_AFTER))
                .build());
      }
    }
    wallet.setBalance(balance);
    wallet.setRevision(revision);
    wallet.setUpdatedAt(now);
    wallets.save(wallet);
    log.info(
        "Cash wallet updated walletId={} userId={} txnId={} revision={}",
        wallet.getId(),
        wallet.getUserId(),
        txn.getId(),
        revision);
  }

  /** What a transaction does to the wallet right now. */
  BigDecimal effect(BiTransaction txn, BiCashWallet wallet) {
    if (!wallet.isActive() || txn.getDeletedAt() != null || txn.getAmount() == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal amount = txn.getAmount().setScale(2, RoundingMode.UNNECESSARY);
    boolean in = txn.getDirection() == TxnDirection.CREDIT;
    if (txn.getMovement() != null) {
      return switch (txn.getMovement()) {
        case BANK_TO_CASH -> amount;
        case CASH_TO_BANK -> amount.negate();
        case BALANCE_CORRECTION -> in ? amount : amount.negate();
        default -> BigDecimal.ZERO;
      };
    }
    if (CASH_WALLET.equals(txn.getPaymentSource())) {
      return in ? amount : amount.negate();
    }
    if (!in && isWithdrawal(txn) && creditedAfterActivation(txn, wallet)) {
      return amount;
    }
    return BigDecimal.ZERO;
  }

  private boolean isWithdrawal(BiTransaction txn) {
    return txn.getCategoryId() != null
        && categories
            .findById(txn.getCategoryId())
            .map(c -> BiCategory.CASH_WITHDRAWAL_CODE.equals(c.getCode()))
            .orElse(false);
  }

  /** Reached the server after activation and dated on or after the activation day. */
  private boolean creditedAfterActivation(BiTransaction txn, BiCashWallet wallet) {
    OffsetDateTime stored = txn.getCreatedAt() == null ? OffsetDateTime.now() : txn.getCreatedAt();
    LocalDate activationDay = wallet.getActivatedAt().atZoneSameInstant(properties.zoneId()).toLocalDate();
    if (wallet.isOpeningCoversDay() && activationDay.equals(txn.getTxnDate())) {
      // The opening count already included that day's withdrawals.
      return false;
    }
    return !stored.isBefore(wallet.getActivatedAt())
        && txn.getTxnDate() != null
        && !txn.getTxnDate().isBefore(activationDay);
  }

  private Kind kindOf(BiTransaction txn) {
    if (txn.getMovement() != null) {
      return switch (txn.getMovement()) {
        case BANK_TO_CASH -> Kind.BANK_TO_CASH;
        case CASH_TO_BANK -> Kind.CASH_TO_BANK;
        default -> Kind.CORRECTION;
      };
    }
    if (CASH_WALLET.equals(txn.getPaymentSource())) {
      return txn.getDirection() == TxnDirection.CREDIT ? Kind.INCOME : Kind.EXPENSE;
    }
    return Kind.WITHDRAWAL;
  }

  /** How long after a withdrawal a bank reversal of it may still arrive. */
  static final int REVERSAL_WINDOW_DAYS = 5;

  /**
   * A bank credit that reverses an ATM withdrawal (a refund/reversal message type): the cash never
   * reached the customer's pocket, so the withdrawal's credit is taken back. The reversal row
   * becomes a confirmed cash-to-bank movement (neither income nor spending), linked to the oldest
   * credited withdrawal of the same amount and bank from up to five days before it, once.
   *
   * <p>If the cash was already spent, the balance goes negative rather than being clamped: a
   * clamped zero would hide that the records and the customer's pocket disagree. The balance check
   * is made due at once, so the app asks the customer to count their cash.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void linkBankReversal(BiTransaction credit) {
    if (credit.getDirection() != TxnDirection.CREDIT
        || credit.getMovement() != null
        || credit.getPaymentSource() != null
        || credit.getDeletedAt() != null
        || credit.getAmount() == null) {
      return;
    }
    String cur;
    try {
      cur = currency(credit.getCurrency());
    } catch (BudgetInsightsException notOurCurrency) {
      return;
    }
    Optional<BiCashWallet> found = locked(credit.getUserId(), cur);
    if (found.isEmpty() || !found.get().isActive()) {
      return;
    }
    BiCashWallet wallet = found.get();
    String bank = bankOf(credit);
    Optional<BiTransaction> withdrawal =
        entries.findLiveByKind(wallet.getId(), Kind.WITHDRAWAL).stream()
            .map(e -> transactions.findByIdAndUserIdAndDeletedAtIsNull(e.getTransactionId(), credit.getUserId()))
            .flatMap(Optional::stream)
            .filter(w -> w.getAmount() != null && w.getAmount().compareTo(credit.getAmount()) == 0)
            .filter(w -> w.getTxnDate() != null && credit.getTxnDate() != null
                && !w.getTxnDate().isAfter(credit.getTxnDate())
                && !w.getTxnDate().isBefore(credit.getTxnDate().minusDays(REVERSAL_WINDOW_DAYS)))
            .filter(w -> bank == null || bankOf(w) == null || bank.equals(bankOf(w)))
            .filter(w -> !entries.existsLiveLink(w.getId()))
            .findFirst();
    if (withdrawal.isEmpty()) {
      return;
    }
    credit.setMovement(CASH_TO_BANK);
    transactions.save(credit);
    sync(credit, false);
    entries.findLiveByTransactionId(credit.getId()).ifPresent(e -> {
      e.setLinkedTransactionId(withdrawal.get().getId());
      entries.save(e);
    });
    BiCashWallet after = wallets.findById(wallet.getId()).orElse(wallet);
    if (after.getBalance().signum() < 0) {
      entries.findLiveByTransactionId(credit.getId()).ifPresent(e ->
          reviews.save(
              BiCashWalletReview.builder()
                  .id(UUID.randomUUID())
                  .walletId(wallet.getId())
                  .userId(wallet.getUserId())
                  .entryId(e.getId())
                  .dueAt(OffsetDateTime.now())
                  .build()));
    }
    log.info(
        "Cash wallet withdrawal reversed by the bank walletId={} userId={} withdrawalTxnId={} reversalTxnId={}",
        wallet.getId(),
        wallet.getUserId(),
        withdrawal.get().getId(),
        credit.getId());
  }

  private String bankOf(BiTransaction txn) {
    return txn.getAccountId() == null
        ? null
        : accounts.findByIdAndUserIdAndDeletedAtIsNull(txn.getAccountId(), txn.getUserId())
            .map(BiAccount::getBankCode)
            .orElse(null);
  }

  // ---------------------------------------------------------------------------------------------
  // Reconciliation
  // ---------------------------------------------------------------------------------------------

  /**
   * The customer's answer to "does this match your cash?": activation (opening amount), a plain
   * confirmation, or a difference explained as spending, a bank deposit, income, a transfer from
   * the bank, or a correction. Applied once per client reference, against the version the customer
   * saw, under the wallet lock.
   */
  @Transactional
  public CashWalletDto reconcile(Caller caller, CashWalletReconcileRequest request) {
    UUID userId = caller.userId();
    String cur = currency(request.getCurrency());
    String clientRef = request.getClientRef();
    if (clientRef == null || !CLIENT_REF.matcher(clientRef).matches()) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
    BigDecimal actual = money(request.getActual());
    if (actual == null || actual.signum() < 0) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
    String hash = hash(request, cur, actual);

    BiCashWallet wallet = lockOrCreate(userId, cur);

    // 1: a reference already applied returns what it produced, whatever happened since.
    Optional<BiCashWalletOperation> done = operations.findByUserIdAndClientRef(userId, clientRef);
    if (done.isPresent()) {
      if (!done.get().getRequestHash().equals(hash)) {
        throw new BudgetInsightsException(ErrorCode.CASH_REFERENCE_REUSED);
      }
      log.info("Cash wallet reconcile retry userId={} (already applied)", userId);
      return toDto(wallet);
    }
    // 2: against the version the customer saw.
    if (!String.valueOf(wallet.getRevision()).equals(request.getExpectedVersion())) {
      throw new BudgetInsightsException(ErrorCode.CASH_STALE);
    }

    OffsetDateTime now = OffsetDateTime.now();
    long revision = wallet.getRevision() + 1;
    String actor = "user:" + userId;
    String reason = request.getReason();
    LocalDate today = LocalDate.now(properties.zoneId());
    LocalDate touched = null;

    if (!wallet.isActive()) {
      // Activation: only an explicit opening amount, never historical arithmetic.
      if (!"CORRECTION".equals(reason)) {
        throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
      }
      wallet.setActivatedAt(now);
      wallet.setOpeningCoversDay(Boolean.TRUE.equals(request.getOpeningIncludesToday()));
      UUID txnId = null;
      if (actual.signum() > 0) {
        BiTransaction opening =
            newTxn(userId, cur, actual, TxnDirection.CREDIT, "Opening cash", otherCategory(), today, clientRef)
                .movement(BALANCE_CORRECTION)
                .build();
        txnId = transactions.save(opening).getId();
      }
      entries.save(
          entry(wallet, Kind.OPENING, actual, actual, revision, txnId, now, actor)
              .reason(reason)
              .previousBalance(BigDecimal.ZERO.setScale(2))
              .statedBalance(actual)
              .build());
      wallet.setBalance(actual);
    } else {
      BigDecimal diff = actual.subtract(wallet.getBalance());
      if (diff.signum() == 0) {
        if (reason != null) {
          throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
        }
      } else {
        BigDecimal size = diff.abs();
        Kind kind;
        BiTransaction txn;
        switch (reason == null ? "" : reason) {
          case "SPENT" -> {
            requireSign(diff, -1);
            BiCategory category = spendCategory(request.getCategoryId());
            txn = newTxn(userId, cur, size, TxnDirection.DEBIT, "Cash spending", category, today, clientRef)
                .paymentSource(CASH_WALLET)
                .isUserCategorised(Boolean.TRUE)
                .categorySource(CategorySource.USER)
                .build();
            kind = Kind.EXPENSE;
          }
          case "INCOME" -> {
            requireSign(diff, 1);
            txn = newTxn(userId, cur, size, TxnDirection.CREDIT, "Cash income", otherCategory(), today, clientRef)
                .paymentSource(CASH_WALLET)
                .build();
            kind = Kind.INCOME;
          }
          case "DEPOSIT" -> {
            requireSign(diff, -1);
            BiAccount account = ownedAccount(userId, request.getAccountId());
            txn = matchOrCreate(userId, cur, size, TxnDirection.CREDIT, account, request.getMatchingTransactionId(),
                CASH_TO_BANK, "Cash deposit", today, clientRef);
            kind = Kind.CASH_TO_BANK;
          }
          case "TRANSFER" -> {
            requireSign(diff, 1);
            BiAccount account = ownedAccount(userId, request.getAccountId());
            txn = matchOrCreate(userId, cur, size, TxnDirection.DEBIT, account, request.getMatchingTransactionId(),
                BANK_TO_CASH, "Bank to cash wallet", today, clientRef);
            kind = Kind.BANK_TO_CASH;
          }
          case "CORRECTION" -> {
            txn = newTxn(userId, cur, size, diff.signum() > 0 ? TxnDirection.CREDIT : TxnDirection.DEBIT,
                    "Cash balance correction", otherCategory(), today, clientRef)
                .movement(BALANCE_CORRECTION)
                .build();
            kind = Kind.CORRECTION;
          }
          default -> throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
        }
        BiTransaction saved = transactions.save(txn);
        touched = saved.getTxnDate();
        entries.save(
            entry(wallet, kind, diff, actual, revision, saved.getId(), now, actor)
                .reason(reason)
                .previousBalance(wallet.getBalance())
                .statedBalance(actual)
                .build());
        wallet.setBalance(actual);
      }
    }

    // 9: every check the customer could see is answered by this review.
    for (BiCashWalletReview open : reviews.findAllByWalletIdAndResolvedAtIsNullOrderByDueAtAsc(wallet.getId())) {
      open.setResolvedAt(now);
      open.setResolvedRevision(revision);
      reviews.save(open);
    }
    wallet.setRevision(revision);
    wallet.setUpdatedAt(now);
    wallets.save(wallet);
    operations.save(
        BiCashWalletOperation.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .clientRef(clientRef)
            .requestHash(hash)
            .walletId(wallet.getId())
            .revisionAfter(revision)
            .createdAt(now)
            .build());

    if (touched != null) {
      caches.invalidateCovering(userId, touched);
      budgetAlerts.evaluate(caller, touched);
    }
    log.info(
        "Cash wallet reconciled walletId={} userId={} reason={} revision={}",
        wallet.getId(),
        userId,
        reason == null ? "CONFIRMED" : reason,
        revision);
    return toDto(wallet);
  }

  /**
   * A bank movement for a deposit or a transfer: the customer's own unlinked imported row when they
   * picked one (checked against owner, account, amount, currency and direction), else a new row.
   * Either way it becomes a confirmed movement, so it is neither spending nor income.
   */
  private BiTransaction matchOrCreate(
      UUID userId,
      String currency,
      BigDecimal amount,
      TxnDirection direction,
      BiAccount account,
      UUID matchingId,
      String movement,
      String label,
      LocalDate today,
      String clientRef) {
    if (matchingId != null) {
      BiTransaction match =
          transactions
              .findByIdAndUserIdAndDeletedAtIsNull(matchingId, userId)
              .orElseThrow(() -> new BudgetInsightsException(ErrorCode.CASH_INVALID));
      boolean fits =
          Objects.equals(match.getAccountId(), account.getId())
              && match.getDirection() == direction
              && currency.equals(currency(match.getCurrency()))
              && match.getAmount() != null
              && match.getAmount().compareTo(amount) == 0
              && match.getMovement() == null
              && match.getPaymentSource() == null
              && !isWithdrawal(match)
              && entries.findLiveByTransactionId(match.getId()).isEmpty();
      if (!fits) {
        throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
      }
      match.setMovement(movement);
      caches.invalidateCovering(userId, match.getTxnDate());
      return match;
    }
    return newTxn(userId, currency, amount, direction, label, transfersCategory(), today, clientRef)
        .accountId(account.getId())
        .movement(movement)
        .build();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  private BiCashWallet lockOrCreate(UUID userId, String currency) {
    Optional<BiCashWallet> existing = locked(userId, currency);
    if (existing.isPresent()) {
      return existing.get();
    }
    OffsetDateTime now = OffsetDateTime.now();
    wallets.saveAndFlush(
        BiCashWallet.builder()
            .id(UUID.randomUUID())
            .userId(userId)
            .currency(currency)
            .balance(BigDecimal.ZERO.setScale(2))
            .revision(0)
            .createdAt(now)
            .build());
    return locked(userId, currency).orElseThrow(() -> new IllegalStateException("cash wallet vanished"));
  }

  /**
   * The wallet row, locked until this transaction ends, with the values read under the lock. The
   * refresh matters: a wallet already loaded earlier in the transaction (to check it is active)
   * would otherwise come back from the lock query with the balance it had before another save
   * committed.
   */
  private Optional<BiCashWallet> locked(UUID userId, String currency) {
    Optional<BiCashWallet> wallet = wallets.lockByUserIdAndCurrency(userId, currency);
    wallet.ifPresent(w -> entityManager.refresh(w, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
    return wallet;
  }

  private boolean isActive(UUID userId, String currency) {
    return wallets.findByUserIdAndCurrency(userId, currency(currency)).map(BiCashWallet::isActive).orElse(false);
  }

  private static BiCashWalletEntry.BiCashWalletEntryBuilder entry(
      BiCashWallet wallet, Kind kind, BigDecimal amount, BigDecimal after, long revision, UUID txnId,
      OffsetDateTime now, String actor) {
    return BiCashWalletEntry.builder()
        .id(UUID.randomUUID())
        .walletId(wallet.getId())
        .userId(wallet.getUserId())
        .kind(kind)
        .amount(amount)
        .balanceAfter(after)
        .revision(revision)
        .transactionId(txnId)
        .actor(actor)
        .createdAt(now);
  }

  private BiTransaction.BiTransactionBuilder newTxn(
      UUID userId, String currency, BigDecimal amount, TxnDirection direction, String merchant,
      BiCategory category, LocalDate date, String clientRef) {
    return BiTransaction.builder()
        .userId(userId)
        .amount(amount)
        .currency(currency)
        .direction(direction)
        .merchant(merchant)
        .txnDate(date)
        .txnTimestamp(OffsetDateTime.now())
        .categoryId(category.getId())
        .autoCategoryId(category.getId())
        .isUserCategorised(Boolean.FALSE)
        .categorySource(CategorySource.RULE)
        .source(TxnSource.MANUAL)
        .idempotencyKey("cash-wallet:" + clientRef);
  }

  private BiCategory spendCategory(UUID categoryId) {
    BiCategory category =
        categoryId == null
            ? otherCategory()
            : categories
                .findById(categoryId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new BudgetInsightsException(ErrorCode.CASH_INVALID));
    requireSpendableCategory(category);
    return category;
  }

  private BiCategory otherCategory() {
    return categories
        .findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE)
        .orElseThrow(() -> new IllegalStateException("The Other category is missing"));
  }

  private BiCategory transfersCategory() {
    return categories.findByCodeAndIsActiveTrue("TRANSFERS").orElseGet(this::otherCategory);
  }

  private BiAccount ownedAccount(UUID userId, UUID accountId) {
    if (accountId == null) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
    return accounts
        .findByIdAndUserIdAndDeletedAtIsNull(accountId, userId)
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.CASH_INVALID));
  }

  private static void requireSign(BigDecimal diff, int sign) {
    if (diff.signum() != sign) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
  }

  private String currency(String requested) {
    String cur = requested == null || requested.isBlank() ? properties.getCurrency() : requested.trim();
    if (!properties.getCurrency().equals(cur)) {
      throw new BudgetInsightsException(ErrorCode.CASH_INVALID);
    }
    return cur;
  }

  /** Exact money: at most two decimals, never rounded. */
  private static BigDecimal money(String raw) {
    if (raw == null || !raw.matches("^\\d{1,15}(\\.\\d{1,2})?$")) {
      return null;
    }
    return new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
  }

  private static String hash(CashWalletReconcileRequest r, String currency, BigDecimal actual) {
    String canonical =
        String.join(
            "|",
            currency,
            actual.toPlainString(),
            String.valueOf(r.getExpectedVersion()),
            String.valueOf(r.getReason()),
            String.valueOf(r.getCategoryId()),
            String.valueOf(r.getAccountId()),
            String.valueOf(r.getMatchingTransactionId()),
            String.valueOf(Boolean.TRUE.equals(r.getOpeningIncludesToday())));
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private CashWalletDto inactive(String currency) {
    return CashWalletDto.builder()
        .currency(currency)
        .active(false)
        .balance("0.00")
        .version("0")
        .recentActivity(List.of())
        .build();
  }

  private CashWalletDto toDto(BiCashWallet wallet) {
    if (!wallet.isActive()) {
      return inactive(wallet.getCurrency()).toBuilder().version(String.valueOf(wallet.getRevision())).build();
    }
    OffsetDateTime due =
        reviews.findAllByWalletIdAndResolvedAtIsNullOrderByDueAtAsc(wallet.getId()).stream()
            .findFirst()
            .map(BiCashWalletReview::getDueAt)
            .orElse(null);
    return CashWalletDto.builder()
        .currency(wallet.getCurrency())
        .active(true)
        .activatedAt(wallet.getActivatedAt().toInstant().toString())
        .balance(wallet.getBalance().setScale(2, RoundingMode.UNNECESSARY).toPlainString())
        .version(String.valueOf(wallet.getRevision()))
        .recentActivity(activityOf(wallet, 0, ACTIVITY_SIZE))
        .reviewDueAt(due == null ? null : due.toInstant().toString())
        .build();
  }

  private List<TransactionDto> activityOf(BiCashWallet wallet, int page, int size) {
    return entries.pageActivity(wallet.getId(), PageRequest.of(page, size)).stream()
        .map(e -> transactions.findByIdAndUserIdAndDeletedAtIsNull(e.getTransactionId(), wallet.getUserId()))
        .flatMap(Optional::stream)
        .map(t -> mapper.toDto(t, t.getAccountId() == null ? null
            : accounts.findByIdAndUserIdAndDeletedAtIsNull(t.getAccountId(), wallet.getUserId()).orElse(null)))
        .toList();
  }
}
