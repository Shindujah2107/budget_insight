package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBillCycle;
import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiBudgetAlert;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillCycleStatus;
import com.moneyreserve.domain.BillStatus;
import com.moneyreserve.domain.BudgetAlertType;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.dto.BillDetailResponse;
import com.moneyreserve.dto.BillDto;
import com.moneyreserve.dto.BillsResponse;
import com.moneyreserve.dto.CreateBillRequest;
import com.moneyreserve.dto.UpdateBillRequest;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiBillCycleRepository;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.BillService;
import com.moneyreserve.service.InsightsMapper;
import com.moneyreserve.service.calc.BillDetector;
import com.moneyreserve.service.calc.BillDetector.Found;
import com.moneyreserve.service.calc.BillDetector.KnownBiller;
import com.moneyreserve.service.calc.BillDetector.Payment;
import com.moneyreserve.service.calc.MerchantTerms;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bills radar. Detection ({@link BillDetector}) runs over the customer's own stored payments within
 * the look-back window; what it finds is kept in {@code bi_user_bill} together with the customer's
 * own choices (a new name, "not a bill", reminder days, bills added by hand), which detection never
 * overrides. Alerts go to the existing alerts list, at most one per bill, due date and kind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillServiceImpl implements BillService {

  private static final String TRANSFERS = "TRANSFERS";

  private final BiUserBillRepository bills;
  private final BiBillCycleRepository cycles;
  private final BiBillerRepository billers;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiBudgetAlertRepository alerts;
  private final InsightsMapper mapper;
  private final InsightsProperties properties;
  private final PlatformTransactionManager transactionManager;
  private final BillDetector detector = new BillDetector();
  private final com.moneyreserve.repository.BiInsightCacheRepository caches;
  private final com.moneyreserve.repository.BiAccountRepository accounts;

  /** Decides whether a bank SMS is a bill payment the customer confirmed (V1.26.0). */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private PaymentPairing pairing;

  /** Records the payment when a bill is marked paid; resolved on use (it also calls back here). */
  private final org.springframework.beans.factory.ObjectProvider<com.moneyreserve.service.InsightsTransactionService>
      transactionService;

  /** A payment this close before the due date already pays that cycle. */
  static final int PAID_EARLY_DAYS = 7;

  /** How long after its due date an unpaid bill added by hand still asks "Did you pay it?". */
  static final int ASK_DAYS_AFTER_DUE = 10;

  @Override
  @Transactional
  public BillsResponse list(Caller caller) {
    refresh(caller.userId(), today());
    return response(caller.userId());
  }

  @Override
  @Transactional(readOnly = true)
  public BillDetailResponse detail(Caller caller, UUID billId) {
    BiUserBill bill = owned(caller, billId);
    List<BillDetailResponse.Paid> paid = new ArrayList<>();
    if (bill.getStatus() != BillStatus.MANUAL) {
      List<KnownBiller> known = known();
      Set<String> seen = new LinkedHashSet<>();
      payments(caller.userId()).stream()
          .filter(p -> !p.moneyIn())
          .filter(p -> BillDetector.payeeKey(p, known).map(bill.getPayeeKey()::equals).orElse(false))
          .sorted(Comparator.comparing(Payment::date).reversed())
          .filter(p -> seen.add(p.date() + "|" + p.amount().stripTrailingZeros().toPlainString()))
          .limit(12)
          .forEach(
              p ->
                  paid.add(
                      BillDetailResponse.Paid.builder()
                          .date(p.date())
                          .amount(mapper.money(p.amount()))
                          .build()));
    }
    BillDetailResponse out = detailOf(caller.userId(), bill);
    out.setPayments(paid);
    return out;
  }

  /** The bill with its cycles, newest first (two years at most). */
  private BillDetailResponse detailOf(UUID userId, BiUserBill bill) {
    List<BiBillCycle> own = cycles.findAllByUserBillIdOrderByDueOnDesc(bill.getId());
    List<BillDetailResponse.Cycle> history = new ArrayList<>();
    for (BiBillCycle c : own.stream().limit(24).toList()) {
      history.add(
          BillDetailResponse.Cycle.builder()
              .id(c.getId())
              .dueOn(c.getDueOn())
              .status(c.getStatus().name())
              .expectedAmount(mapper.money(c.getExpectedAmount()))
              .actualAmount(c.getActualAmount() == null ? null : mapper.money(c.getActualAmount()))
              .paidOn(c.getPaidOn())
              .build());
    }
    return BillDetailResponse.builder()
        .bill(toDto(bill, billerById(), today(), own))
        .payments(new ArrayList<>())
        .cycles(history)
        .build();
  }

  /** The bill as the app shows it, with its own cycles. */
  private BillDto dto(BiUserBill bill) {
    return toDto(bill, billerById(), today(), cycles.findAllByUserBillIdOrderByDueOnDesc(bill.getId()));
  }

  @Override
  @Transactional
  public BillDto create(Caller caller, CreateBillRequest request) {
    InsightsProperties.Bills config = properties.getBills();
    if (config.isPremiumGate()
        && bills.countByUserIdAndStatus(caller.userId(), BillStatus.MANUAL) >= config.getFreeManualBills()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_PREMIUM);
    }
    String name = request.name().trim();
    if (name.isEmpty()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID);
    }
    UUID id = UUID.randomUUID();
    LocalDate due = request.nextDueOn();
    BiUserBill bill =
        BiUserBill.builder()
            .id(id)
            .userId(caller.userId())
            .payeeKey("X:" + id)
            .detectedName(name)
            .status(BillStatus.MANUAL)
            .cadence(BillCadence.valueOf(request.cadence()))
            .expectedAmount(request.expectedAmount().setScale(2, RoundingMode.HALF_UP))
            .categoryId(chosenCategory(request.categoryId()))
            .nextDueOn(due)
            .anchorDay((short) (due.getDayOfMonth() == due.lengthOfMonth() ? 31 : due.getDayOfMonth()))
            .remindDaysBefore(remindDays(request.remindDaysBefore()))
            .paymentCount((short) 0)
            .createdAt(OffsetDateTime.now())
            .build();
    bill = bills.save(bill);
    ensureCycle(bill, due, false);
    log.info("Insights bill added by hand userId={} billId={}", caller.userId(), id);
    return dto(bill);
  }

  @Override
  @Transactional
  public BillDto update(Caller caller, UUID billId, UpdateBillRequest request) {
    BiUserBill bill =
        bills
            .lockByIdAndUserId(billId, caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    boolean manual = bill.getStatus() == BillStatus.MANUAL;
    boolean ownSchedule = ownsSchedule(bill);
    if (!ownSchedule
        && (request.expectedAmount() != null || request.cadence() != null || request.nextDueOn() != null)) {
      throw new BudgetInsightsException(
          ErrorCode.INSIGHTS_BILL_INVALID, "A found bill takes its amount and dates from its payments.");
    }
    if (request.name() != null) {
      String name = request.name().trim();
      if (manual && name.isEmpty()) {
        throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID);
      }
      if (manual) {
        bill.setDetectedName(name);
      } else {
        bill.setCustomName(name.isEmpty() ? null : name);
      }
    }
    if (Boolean.TRUE.equals(request.confirm()) && bill.getStatus() == BillStatus.DETECTED) {
      bill.setStatus(BillStatus.CONFIRMED);
    }
    if (Boolean.TRUE.equals(request.dismiss()) && !manual) {
      bill.setStatus(BillStatus.DISMISSED);
    }
    if (request.remindDaysBefore() != null) {
      bill.setRemindDaysBefore(remindDays(request.remindDaysBefore()));
    }
    if (request.categoryId() != null) {
      bill.setCategoryId(chosenCategory(request.categoryId()));
    }
    if (ownSchedule) {
      if (request.expectedAmount() != null) {
        bill.setExpectedAmount(request.expectedAmount().setScale(2, RoundingMode.HALF_UP));
      }
      if (request.cadence() != null) {
        bill.setCadence(BillCadence.valueOf(request.cadence()));
      }
      if (request.nextDueOn() != null && !request.nextDueOn().equals(bill.getNextDueOn())) {
        LocalDate due = request.nextDueOn();
        // The current cycle moves with the date while nothing has happened to it; an answered one
        // stays in the history.
        cycles
            .findByUserBillIdAndDueOn(bill.getId(), bill.getNextDueOn())
            .filter(c -> c.getStatus() == BillCycleStatus.DUE && c.getTransactionId() == null)
            .ifPresent(cycles::delete);
        bill.setNextDueOn(due);
        bill.setAnchorDay((short) (due.getDayOfMonth() == due.lengthOfMonth() ? 31 : due.getDayOfMonth()));
      }
    }
    bill.setUpdatedAt(OffsetDateTime.now());
    bill = bills.save(bill);
    if (bill.getStatus() != BillStatus.DISMISSED) {
      cycles.flush();
      ensureCycle(bill, bill.getNextDueOn(), false);
    }
    log.info("Insights bill updated userId={} billId={} status={}", caller.userId(), billId, bill.getStatus());
    return dto(bill);
  }

  @Override
  @Transactional
  public BillsResponse delete(Caller caller, UUID billId) {
    BiUserBill bill = owned(caller, billId);
    if (bill.getStatus() == BillStatus.MANUAL) {
      cycles.deleteAllForBill(bill.getId());
      bills.delete(bill);
    } else {
      bill.setStatus(BillStatus.DISMISSED);
      bill.setUpdatedAt(OffsetDateTime.now());
      bills.save(bill);
    }
    log.info("Insights bill removed userId={} billId={}", caller.userId(), billId);
    return response(caller.userId());
  }

  /**
   * Marks this cycle of a bill paid, once (V1.23.0). The payment is recorded in the bill's
   * category and linked to the bill; if the bank's SMS for it is already stored, that SMS is the
   * payment instead (paired, nothing added). A cycle already paid records nothing.
   */
  @Override
  @Transactional
  public com.moneyreserve.dto.BillPaidResponse markPaid(
      Caller caller, UUID billId, com.moneyreserve.dto.MarkBillPaidRequest request) {
    // One at a time per bill (V1.26.0): the lock is taken before the cycle is checked, so two
    // taps, two phones or a retry arriving together settle the cycle once. The second waits here
    // and then finds the cycle paid.
    BiUserBill bill =
        bills
            .lockByIdAndUserId(billId, caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    if (bill.getStatus() == BillStatus.DISMISSED || bill.getStatus() == BillStatus.SUGGESTED) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND); // a suggestion is not a bill yet
    }
    LocalDate today = today();
    LocalDate paidOn = request.paidOn() == null ? today : request.paidOn();

    // Which cycle: one from the bill's history, the due date the customer was shown, or the
    // current one.
    BiBillCycle cycle;
    UUID ownBill = bill.getId();
    if (request.cycleId() != null) {
      cycle =
          cycles
              .findByIdAndUserId(request.cycleId(), caller.userId())
              .filter(c -> c.getUserBillId().equals(ownBill))
              .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    } else if (request.dueOn() != null && !request.dueOn().equals(bill.getNextDueOn())) {
      // A screen showing a due date the bill has since moved past (paid from another phone, or
      // from this one a moment ago): that cycle, or "already paid", never the next cycle.
      Optional<BiBillCycle> shown = cycles.findByUserBillIdAndDueOn(bill.getId(), request.dueOn());
      if (shown.isEmpty()) {
        return alreadyPaid(caller, bill);
      }
      cycle = shown.get();
    } else {
      cycle = ensureCycle(bill, bill.getNextDueOn(), true);
      // An older app sends no due date: a second "Paid" soon after the last one is the same cycle.
      if (request.dueOn() == null && cycle.getStatus().open() && paidRecently(bill, paidOn)) {
        return alreadyPaid(caller, bill);
      }
    }
    if (!cycle.getStatus().open()) {
      return alreadyPaid(caller, bill);
    }
    boolean current = cycle.getDueOn().equals(bill.getNextDueOn());

    BigDecimal amount =
        (request.amount() == null ? cycle.getExpectedAmount() : request.amount()).setScale(2, RoundingMode.HALF_UP);
    com.moneyreserve.dto.CreateTransactionRequest payment =
        com.moneyreserve.dto.CreateTransactionRequest.builder()
            .amount(amount)
            .currency(properties.getCurrency())
            .direction("DEBIT")
            .merchant(clip(bill.getDetectedName(), 128))
            .txnDate(paidOn)
            .categoryId(
                request.categoryId() != null
                    ? request.categoryId()
                    : categoriesOn() && bill.getCategoryId() != null ? bill.getCategoryId() : billCategory(bill))
            .clientRef(request.clientRef())
            .origin(request.originBankCode() != null ? PaymentPairing.ATTEMPT : PaymentPairing.BILL)
            .originBankCode(request.originBankCode())
            .userBillId(bill.getId())
            .build();
    com.moneyreserve.dto.TransactionMutationResponse recorded =
        transactionService.getObject().create(caller, payment, null, null);
    UUID paymentId = recorded.getTransaction().getId();
    // The payment this request resolved to (a retried clientRef, or a bank SMS) already settles
    // another cycle: one payment never settles two.
    Optional<BiBillCycle> elsewhere = cycles.findByTransactionId(paymentId);
    if (elsewhere.isPresent() && !elsewhere.get().getId().equals(cycle.getId())) {
      log.info("Insights bill payment already settles another cycle userId={} billId={}", caller.userId(), billId);
      return com.moneyreserve.dto.BillPaidResponse.builder()
          .alreadyPaid(Boolean.TRUE)
          .payment(recorded)
          .bill(dto(bill))
          .build();
    }
    LocalDate settledOn = recorded.getTransaction().getDate() == null ? paidOn : recorded.getTransaction().getDate();
    cycle.setStatus(BillCycleStatus.PAID);
    cycle.setActualAmount(new BigDecimal(recorded.getTransaction().getAmount()).setScale(2, RoundingMode.HALF_UP));
    cycle.setPaidOn(settledOn);
    cycle.setTransactionId(paymentId);
    cycle.setSettledBy(BiBillCycle.MARKED);
    cycle.setUpdatedAt(OffsetDateTime.now());
    cycles.save(cycle);
    if (ownsSchedule(bill)) {
      if (bill.getLastPaidOn() == null || !settledOn.isBefore(bill.getLastPaidOn())) {
        bill.setLastPaidOn(settledOn);
        bill.setLastAmount(cycle.getActualAmount());
      }
      bill.setPaymentCount((short) Math.min(bill.getPaymentCount() + 1, Short.MAX_VALUE));
      if (current) {
        // On to the next due date, exactly one cycle: each due date is its own cycle.
        bill.setNextDueOn(advance(bill));
      }
      bill.setUpdatedAt(OffsetDateTime.now());
      bill = bills.save(bill);
      ensureCycle(bill, bill.getNextDueOn(), false);
    }
    refreshAfterCommit(caller.userId());
    log.info("Insights bill marked paid userId={} billId={} cycleId={}", caller.userId(), billId, cycle.getId());
    // A stored bank SMS that may be this payment but was not certain enough to link: ask once.
    com.moneyreserve.dto.BillMatchDto possible = null;
    if (pairing != null && !Boolean.TRUE.equals(recorded.getDuplicate())) {
      Optional<PaymentPairing.BillMatch> maybe =
          pairing.smsForBill(caller.userId(), request.originBankCode(), bill.getId(), amount, paidOn, paymentId);
      if (maybe.isPresent()) {
        BiTransaction sms = maybe.get().sms();
        possible = matchDto(bill, sms, paymentId, false);
      }
    }
    return com.moneyreserve.dto.BillPaidResponse.builder()
        .payment(recorded)
        .bill(dto(bill))
        .possibleSms(possible)
        .build();
  }

  private com.moneyreserve.dto.BillPaidResponse alreadyPaid(Caller caller, BiUserBill bill) {
    log.info("Insights bill already paid this cycle userId={} billId={}", caller.userId(), bill.getId());
    return com.moneyreserve.dto.BillPaidResponse.builder().alreadyPaid(Boolean.TRUE).bill(dto(bill)).build();
  }

  @Override
  @Transactional
  public BillDetailResponse skipCycle(Caller caller, UUID billId, UUID cycleId) {
    BiUserBill bill =
        bills
            .lockByIdAndUserId(billId, caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    BiBillCycle cycle =
        cycles
            .findByIdAndUserId(cycleId, caller.userId())
            .filter(c -> c.getUserBillId().equals(bill.getId()))
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    // Only a cycle no longer being asked about: the current one has "Not yet" instead.
    if (cycle.getStatus() != BillCycleStatus.NOT_CONFIRMED) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID, "Only a payment not confirmed can be marked not paid.");
    }
    cycle.setStatus(BillCycleStatus.SKIPPED);
    cycle.setSettledBy(BiBillCycle.SKIPPED);
    cycle.setUpdatedAt(OffsetDateTime.now());
    cycles.save(cycle);
    log.info("Insights bill cycle marked not paid userId={} billId={} cycleId={}", caller.userId(), billId, cycleId);
    return detailOf(caller.userId(), bill);
  }

  @Override
  @Transactional
  public com.moneyreserve.dto.BillMatchDto smsMatched(UUID userId, BiTransaction sms, BiTransaction recorded, boolean link) {
    if (recorded.getUserBillId() == null) {
      return null;
    }
    Optional<BiUserBill> bill = bills.lockByIdAndUserId(recorded.getUserBillId(), userId);
    if (bill.isEmpty()) {
      return null;
    }
    boolean linked = link && linkSms(bill.get(), sms, recorded);
    log.info(
        "Insights bank SMS fits a confirmed bill payment userId={} billId={} linked={}", userId, bill.get().getId(), linked);
    return matchDto(bill.get(), sms, recorded.getId(), linked);
  }

  @Override
  @Transactional
  public BillDto matchSms(Caller caller, UUID billId, com.moneyreserve.dto.BillSmsMatchRequest request) {
    UUID userId = caller.userId();
    BiUserBill bill =
        bills.lockByIdAndUserId(billId, userId).orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    if (request.recordedTransactionId() == null) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID);
    }
    BiTransaction sms = transactions.findByIdAndUserId(request.smsTransactionId(), userId).orElse(null);
    BiTransaction recorded = transactions.findByIdAndUserId(request.recordedTransactionId(), userId).orElse(null);
    boolean fits =
        sms != null
            && recorded != null
            && billId.equals(recorded.getUserBillId())
            && recorded.getOrigin() != null
            && Math.abs(ChronoUnit.DAYS.between(recorded.getTxnDate(), sms.getTxnDate())) <= PaymentPairing.BILL_WINDOW_DAYS
            && pairing != null
            && pairing.couldBe(billId, recorded.getAmount(), sms.getAmount());
    if (!fits || !linkSms(bill, sms, recorded)) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID, "That bank payment can't be matched to this bill.");
    }
    log.info("Insights bank SMS matched to a bill payment by the customer userId={} billId={}", userId, billId);
    return dto(bill);
  }

  @Override
  @Transactional
  public BillDto unmatchSms(Caller caller, UUID billId, com.moneyreserve.dto.BillSmsMatchRequest request) {
    UUID userId = caller.userId();
    BiUserBill bill =
        bills.lockByIdAndUserId(billId, userId).orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    BiTransaction sms =
        transactions
            .findByIdAndUserId(request.smsTransactionId(), userId)
            .filter(t -> billId.equals(t.getUserBillId()) && t.getLinkedKey() != null && t.getDeletedAt() == null)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID, "That payment isn't matched to this bill."));
    BiTransaction recorded =
        transactions
            .findByUserIdAndIdempotencyKey(userId, sms.getLinkedKey())
            .filter(t -> billId.equals(t.getUserBillId()) && t.getDeletedAt() != null)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID, "That payment isn't matched to this bill."));
    Optional<BiBillCycle> cycle = cycles.findByTransactionId(sms.getId());
    // The customer's own payment back, the bank SMS its own transaction again (in the category the
    // engine gave it). Nothing is counted twice or lost: one of the two is live at any moment.
    recorded.setDeletedAt(null);
    sms.setUserBillId(null);
    sms.setLinkedKey(null);
    sms.setCategoryId(sms.getAutoCategoryId());
    sms.setIsUserCategorised(Boolean.FALSE);
    sms.setCategorySource(com.moneyreserve.domain.CategorySource.RULE);
    transactions.save(sms);
    transactions.save(recorded);
    cycle.ifPresent(
        c -> {
          c.setTransactionId(recorded.getId());
          c.setActualAmount(recorded.getAmount().setScale(2, RoundingMode.HALF_UP));
          c.setPaidOn(recorded.getTxnDate());
          c.setUpdatedAt(OffsetDateTime.now());
          cycles.save(c);
        });
    invalidate(userId, sms.getTxnDate(), recorded.getTxnDate());
    log.info("Insights bank SMS unmatched from a bill payment userId={} billId={}", userId, billId);
    return dto(bill);
  }

  /**
   * Makes the bank SMS the bill payment the customer recorded: the SMS takes the payment's bill,
   * category and key (a retried confirmation stays a duplicate), the recorded payment is set aside
   * (soft deleted, kept for Undo), and the cycle points at the SMS with the bank's amount and date.
   * Callers hold the bill's lock. False when either side is no longer free to link.
   */
  private boolean linkSms(BiUserBill bill, BiTransaction sms, BiTransaction recorded) {
    Optional<BiBillCycle> cycle = cycles.findByTransactionId(recorded.getId());
    boolean free =
        cycle.isPresent()
            && cycle.get().getStatus() == BillCycleStatus.PAID
            && bill.getId().equals(recorded.getUserBillId())
            && recorded.getDeletedAt() == null
            && recorded.getLinkedKey() == null
            && sms.getDeletedAt() == null
            && sms.getUserBillId() == null
            && sms.getLinkedKey() == null
            && sms.getOrigin() == null
            && sms.getMovement() == null
            && sms.getSource() != com.moneyreserve.domain.TxnSource.MANUAL
            && sms.getDirection() == TxnDirection.DEBIT
            && cycles.findByTransactionId(sms.getId()).isEmpty();
    if (!free) {
      return false;
    }
    sms.setUserBillId(bill.getId());
    sms.setLinkedKey(recorded.getIdempotencyKey());
    if (recorded.getCategoryId() != null && !recorded.getCategoryId().equals(sms.getCategoryId())) {
      sms.setCategoryId(recorded.getCategoryId());
      sms.setIsUserCategorised(Boolean.TRUE);
      sms.setCategorySource(com.moneyreserve.domain.CategorySource.USER);
    }
    recorded.setDeletedAt(OffsetDateTime.now());
    transactions.save(recorded);
    transactions.save(sms);
    BiBillCycle c = cycle.get();
    c.setTransactionId(sms.getId());
    c.setActualAmount(sms.getAmount().setScale(2, RoundingMode.HALF_UP));
    c.setPaidOn(sms.getTxnDate());
    c.setUpdatedAt(OffsetDateTime.now());
    cycles.save(c);
    invalidate(bill.getUserId(), sms.getTxnDate(), recorded.getTxnDate());
    return true;
  }

  private com.moneyreserve.dto.BillMatchDto matchDto(BiUserBill bill, BiTransaction sms, UUID recordedId, boolean linked) {
    return com.moneyreserve.dto.BillMatchDto.builder()
        .billId(bill.getId())
        .billName(bill.displayName())
        .smsTransactionId(sms.getId())
        .recordedTransactionId(recordedId)
        .amount(mapper.money(sms.getAmount()))
        .date(sms.getTxnDate())
        .linked(linked)
        .build();
  }

  private void invalidate(UUID userId, LocalDate... dates) {
    for (LocalDate d : dates) {
      if (d != null) {
        caches.invalidateCovering(userId, d);
      }
    }
  }

  @Override
  @Transactional
  public void paymentRemoved(UUID userId, UUID transactionId) {
    Optional<BiBillCycle> settled = cycles.findByTransactionId(transactionId).filter(c -> c.getUserId().equals(userId));
    if (settled.isEmpty()) {
      return;
    }
    BiBillCycle cycle = settled.get();
    Optional<BiUserBill> bill = bills.lockByIdAndUserId(cycle.getUserBillId(), userId);
    LocalDate today = today();
    // Answerable again: still asked about while it is the current due date and within the asking
    // days, otherwise "Payment not confirmed".
    boolean asking =
        bill.isPresent()
            && cycle.getDueOn().equals(bill.get().getNextDueOn())
            && !today.isAfter(cycle.getDueOn().plusDays(ASK_DAYS_AFTER_DUE));
    cycle.setStatus(asking ? BillCycleStatus.DUE : BillCycleStatus.NOT_CONFIRMED);
    cycle.setActualAmount(null);
    cycle.setPaidOn(null);
    cycle.setTransactionId(null);
    cycle.setSettledBy(null);
    cycle.setUpdatedAt(OffsetDateTime.now());
    cycles.save(cycle);
    log.info("Insights bill cycle reopened: its payment was removed userId={} cycleId={}", userId, cycle.getId());
  }

  @Override
  @Transactional
  public void paymentEdited(UUID userId, BiTransaction payment) {
    cycles
        .findByTransactionId(payment.getId())
        .filter(c -> c.getUserId().equals(userId))
        .ifPresent(
            c -> {
              c.setActualAmount(payment.getAmount().setScale(2, RoundingMode.HALF_UP));
              c.setPaidOn(payment.getTxnDate());
              c.setUpdatedAt(OffsetDateTime.now());
              cycles.save(c);
            });
  }

  // ---------------------------------------------------------------------------------
  // Suggestions (V1.27.0)
  // ---------------------------------------------------------------------------------

  /** Categories whose regular payments are shopping, not bills: never suggested. */
  private static final Set<String> NEVER_SUGGESTED = Set.of("GROCERIES", "FUEL", "DINING_OUT", "FOOD", "SHOPPING");

  /** How close a suggestion's payments must be to each other, without a known biller (%). */
  static final int SUGGEST_TOLERANCE_PCT = 10;

  /**
   * Clear enough to suggest: a known biller (its own rules); otherwise three or more payments
   * (the detector has already checked they are evenly spaced), all within
   * {@link #SUGGEST_TOLERANCE_PCT}% of their median, none in a shopping category.
   */
  static boolean suggestable(Found f) {
    List<Payment> paid = f.evidence() == null ? List.of() : f.evidence();
    if (paid.stream().anyMatch(p -> p.categoryCode() != null && NEVER_SUGGESTED.contains(p.categoryCode()))) {
      return false;
    }
    if (f.biller() != null) {
      return true;
    }
    if (paid.size() < 3) {
      return false;
    }
    List<BigDecimal> recent = paid.subList(Math.max(0, paid.size() - 6), paid.size()).stream().map(Payment::amount).sorted().toList();
    BigDecimal mid = recent.get(recent.size() / 2);
    BigDecimal allowed = mid.multiply(BigDecimal.valueOf(SUGGEST_TOLERANCE_PCT)).divide(BigDecimal.valueOf(100));
    return recent.stream().allMatch(a -> a.subtract(mid).abs().compareTo(allowed) <= 0);
  }

  /** Up to the last 12 payment ids, comma-separated. */
  private static String evidence(Found f) {
    if (f.evidence() == null) {
      return null;
    }
    List<Payment> paid = f.evidence();
    String ids =
        paid.subList(Math.max(0, paid.size() - 12), paid.size()).stream()
            .map(Payment::id)
            .filter(Objects::nonNull)
            .map(UUID::toString)
            .collect(java.util.stream.Collectors.joining(","));
    return ids.isEmpty() ? null : ids;
  }

  private static UUID lastId(Found f) {
    return f.evidence() == null || f.evidence().isEmpty() ? null : f.evidence().get(f.evidence().size() - 1).id();
  }

  /**
   * A tracked bill's open cycles, each settled by its payee's payment around that due date (from a
   * week before to the end of the asking days), one payment per cycle, closest to the due date.
   * Needs no regular pattern: the customer set the schedule.
   */
  private void settleFromPayments(BiUserBill b, List<Payment> payments, List<KnownBiller> known) {
    List<BiBillCycle> open =
        new ArrayList<>(cycles.findAllByUserBillIdAndStatusIn(b.getId(), List.of(BillCycleStatus.DUE, BillCycleStatus.NOT_CONFIRMED)));
    if (b.getNextDueOn() != null && open.stream().noneMatch(c -> c.getDueOn().equals(b.getNextDueOn()))) {
      BiBillCycle current = ensureCycle(b, b.getNextDueOn(), false);
      if (current.getStatus().open()) {
        open.add(current);
      }
    }
    open.sort(Comparator.comparing(BiBillCycle::getDueOn));
    Set<UUID> used = new HashSet<>();
    for (BiBillCycle c : open) {
      LocalDate from = c.getDueOn().minusDays(PAID_EARLY_DAYS);
      LocalDate to = c.getDueOn().plusDays(ASK_DAYS_AFTER_DUE);
      Optional<Payment> pay =
          payments.stream()
              .filter(p -> !p.moneyIn() && p.id() != null && !used.contains(p.id()))
              .filter(p -> !p.date().isBefore(from) && !p.date().isAfter(to))
              .filter(p -> BillDetector.payeeKey(p, known).map(b.getPayeeKey()::equals).orElse(false))
              .filter(p -> cycles.findByTransactionId(p.id()).isEmpty())
              .min(Comparator.comparingLong(p -> Math.abs(ChronoUnit.DAYS.between(c.getDueOn(), p.date()))));
      if (pay.isEmpty()) {
        continue;
      }
      Payment p = pay.get();
      used.add(p.id());
      c.setStatus(BillCycleStatus.PAID);
      c.setPaidOn(p.date());
      c.setActualAmount(p.amount().setScale(2, RoundingMode.HALF_UP));
      c.setTransactionId(p.id());
      c.setSettledBy(BiBillCycle.FOUND);
      c.setUpdatedAt(OffsetDateTime.now());
      cycles.save(c);
      if (b.getLastPaidOn() == null || p.date().isAfter(b.getLastPaidOn())) {
        b.setLastPaidOn(p.date());
        b.setLastAmount(p.amount());
      }
    }
  }

  /** The customer owns the amount, repeat and due date: added by hand, or tracked. */
  private static boolean ownsSchedule(BiUserBill b) {
    return b.getStatus() == BillStatus.MANUAL || b.isUserSchedule();
  }

  @Override
  @Transactional(readOnly = true)
  public com.moneyreserve.dto.BillSuggestionResponse suggestion(Caller caller, UUID billId) {
    if (!suggestionsOn()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_PREMIUM);
    }
    BiUserBill bill = owned(caller, billId);
    if (bill.getStatus() != BillStatus.SUGGESTED) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND);
    }
    List<com.moneyreserve.dto.BillSuggestionResponse.Evidence> rows = new ArrayList<>();
    if (bill.getEvidence() != null) {
      for (String id : bill.getEvidence().split(",")) {
        UUID txnId;
        try {
          txnId = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
          continue;
        }
        transactions
            .findByIdAndUserId(txnId, caller.userId())
            .filter(t -> t.getDeletedAt() == null)
            .ifPresent(
                t -> {
                  com.moneyreserve.domain.BiAccount account =
                      t.getAccountId() == null ? null : accounts.findById(t.getAccountId()).orElse(null);
                  rows.add(
                      com.moneyreserve.dto.BillSuggestionResponse.Evidence.builder()
                          .transactionId(t.getId())
                          .date(t.getTxnDate())
                          .amount(mapper.money(t.getAmount()))
                          .description(t.getMerchant())
                          .accountMask(account == null ? null : account.getMaskedTail())
                          .accountKind(account == null || account.getInstrumentType() == null ? null : account.getInstrumentType().name())
                          .build());
                });
      }
    }
    rows.sort(Comparator.comparing(com.moneyreserve.dto.BillSuggestionResponse.Evidence::getDate).reversed());
    long months = rows.stream().map(r -> java.time.YearMonth.from(r.getDate())).distinct().count();
    return com.moneyreserve.dto.BillSuggestionResponse.builder()
        .bill(toDto(bill, billerById(), today(), List.of()))
        .payments(rows)
        .months((int) months)
        .build();
  }

  @Override
  @Transactional
  public BillDto track(Caller caller, UUID billId, CreateBillRequest request) {
    if (!suggestionsOn()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_PREMIUM);
    }
    BiUserBill bill =
        bills
            .lockByIdAndUserId(billId, caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
    if (bill.getStatus() != BillStatus.SUGGESTED) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID, "Only a suggestion can be tracked.");
    }
    String name = request.name().trim();
    if (name.isEmpty()) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID);
    }
    LocalDate due = request.nextDueOn();
    bill.setStatus(BillStatus.CONFIRMED);
    bill.setUserSchedule(true);
    bill.setCustomName(name.equals(bill.getDetectedName()) ? null : clip(name, 64));
    bill.setExpectedAmount(request.expectedAmount().setScale(2, RoundingMode.HALF_UP));
    bill.setCadence(BillCadence.valueOf(request.cadence()));
    bill.setNextDueOn(due);
    bill.setAnchorDay((short) (due.getDayOfMonth() == due.lengthOfMonth() ? 31 : due.getDayOfMonth()));
    bill.setRemindDaysBefore(remindDays(request.remindDaysBefore()));
    if (request.categoryId() != null) {
      bill.setCategoryId(chosenCategory(request.categoryId()));
    }
    bill.setUpdatedAt(OffsetDateTime.now());
    bill = bills.save(bill);
    ensureCycle(bill, due, false);
    log.info("Insights bill suggestion tracked userId={} billId={}", caller.userId(), billId);
    return dto(bill);
  }

  // ---------------------------------------------------------------------------------
  // Cycles (V1.26.0)
  // ---------------------------------------------------------------------------------

  /**
   * The cycle of {@code bill} due on {@code due}, created when first needed. A new one starts as
   * paid only when the bill's own stored last payment already covers that due date (the rule
   * "paid this cycle" used before cycles existed): a fact from the customer's records, never a
   * guess. Callers hold the bill's lock.
   */
  private BiBillCycle ensureCycle(BiUserBill bill, LocalDate due, boolean fromRecords) {
    Optional<BiBillCycle> existing = cycles.findByUserBillIdAndDueOn(bill.getId(), due);
    if (existing.isPresent()) {
      return existing.get();
    }
    OffsetDateTime now = OffsetDateTime.now();
    BiBillCycle cycle =
        BiBillCycle.builder()
            .id(UUID.randomUUID())
            .userId(bill.getUserId())
            .userBillId(bill.getId())
            .dueOn(due)
            .expectedAmount(bill.getExpectedAmount())
            .status(BillCycleStatus.DUE)
            .createdAt(now)
            .build();
    LocalDate last = bill.getLastPaidOn();
    if (fromRecords && last != null && !last.isBefore(due.minusDays(PAID_EARLY_DAYS)) && !last.isAfter(due.plusDays(cycleDays(bill)))) {
      cycle.setStatus(BillCycleStatus.PAID);
      cycle.setPaidOn(last);
      cycle.setActualAmount(bill.getLastAmount());
      cycle.setSettledBy(BiBillCycle.RECORDED);
      cycle.setTransactionId(recordedPayment(bill, last));
    }
    return cycles.save(cycle);
  }

  /** The one payment linked to this bill on that date and settling no cycle yet; else none. */
  private UUID recordedPayment(BiUserBill bill, LocalDate on) {
    List<BiTransaction> linked =
        transactions.findAllByUserIdAndUserBillIdAndDeletedAtIsNull(bill.getUserId(), bill.getId()).stream()
            .filter(t -> on.equals(t.getTxnDate()))
            .filter(t -> cycles.findByTransactionId(t.getId()).isEmpty())
            .toList();
    return linked.size() == 1 ? linked.get(0).getId() : null;
  }

  /** Roughly how long one cycle lasts, in days. */
  private static int cycleDays(BiUserBill bill) {
    return switch (bill.getCadence()) {
      case WEEKLY -> 7;
      case MONTHLY -> 31;
      case QUARTERLY -> 92;
      case YEARLY -> 366;
    };
  }

  /**
   * A found bill after detection ran: the newest payment settles the latest open cycle it covers
   * (a late bank SMS answers a cycle that was "Payment not confirmed"), a cycle settled by a
   * payment that is no longer there opens again, and a due date the bill moved past unpaid is
   * "Payment not confirmed".
   */
  private void syncFoundCycles(
      BiUserBill b, LocalDate previousDue, boolean newPayment, LocalDate today, UUID paymentId) {
    LocalDate last = b.getLastPaidOn();
    OffsetDateTime now = OffsetDateTime.now();
    for (BiBillCycle c : cycles.findAllByUserBillIdOrderByDueOnDesc(b.getId())) {
      if (c.getStatus() == BillCycleStatus.PAID
          && BiBillCycle.FOUND.equals(c.getSettledBy())
          && (last == null || c.getPaidOn().isAfter(last))) {
        boolean asking = c.getDueOn().equals(b.getNextDueOn()) && !today.isAfter(c.getDueOn().plusDays(ASK_DAYS_AFTER_DUE));
        c.setStatus(asking ? BillCycleStatus.DUE : BillCycleStatus.NOT_CONFIRMED);
        c.setPaidOn(null);
        c.setActualAmount(null);
        c.setSettledBy(null);
        c.setUpdatedAt(now);
        cycles.save(c);
      }
    }
    List<BiBillCycle> all = cycles.findAllByUserBillIdOrderByDueOnDesc(b.getId());
    boolean counted = all.stream().anyMatch(c -> c.getStatus() == BillCycleStatus.PAID && last != null && last.equals(c.getPaidOn()));
    if (newPayment && last != null && !counted) {
      cycles.findAllByUserBillIdAndStatusIn(b.getId(), List.of(BillCycleStatus.DUE, BillCycleStatus.NOT_CONFIRMED)).stream()
          .filter(c -> !c.getDueOn().isAfter(last.plusDays(PAID_EARLY_DAYS)))
          .filter(c -> c.getDueOn().isAfter(last.minusDays(cycleDays(b))))
          .max(Comparator.comparing(BiBillCycle::getDueOn))
          .ifPresent(
              c -> {
                c.setStatus(BillCycleStatus.PAID);
                c.setPaidOn(last);
                c.setActualAmount(b.getLastAmount());
                c.setSettledBy(BiBillCycle.FOUND);
                if (paymentId != null && cycles.findByTransactionId(paymentId).isEmpty()) {
                  c.setTransactionId(paymentId);
                }
                c.setUpdatedAt(now);
                cycles.save(c);
              });
    }
    if (previousDue != null && !previousDue.equals(b.getNextDueOn())) {
      cycles
          .findByUserBillIdAndDueOn(b.getId(), previousDue)
          .filter(c -> c.getStatus() == BillCycleStatus.DUE)
          .ifPresent(
              c -> {
                if (b.getNextDueOn().isBefore(previousDue) && c.getTransactionId() == null) {
                  cycles.delete(c); // detection moved the date back: that due date never was
                } else {
                  c.setStatus(BillCycleStatus.NOT_CONFIRMED);
                  c.setUpdatedAt(now);
                  cycles.save(c);
                }
              });
    }
  }

  /**
   * The current cycle once its asking days are over: no answer means "Payment not confirmed"
   * (reminders stop; it stays answerable). A bill added by hand then moves on to its next due
   * date, each due date its own cycle.
   */
  private void rollOver(BiUserBill b, LocalDate today) {
    if (ownsSchedule(b)) {
      boolean moved = false;
      for (int guard = 0; guard < 240; guard++) {
        BiBillCycle c = ensureCycle(b, b.getNextDueOn(), b.getStatus() == BillStatus.MANUAL);
        // A tracked bill's current cycle settled by its bank SMS moves on, as "Paid" does.
        boolean settled = b.getStatus() != BillStatus.MANUAL && c.getStatus() == BillCycleStatus.PAID;
        if (!settled && !today.isAfter(b.getNextDueOn().plusDays(ASK_DAYS_AFTER_DUE))) {
          break;
        }
        if (c.getStatus() == BillCycleStatus.DUE) {
          c.setStatus(BillCycleStatus.NOT_CONFIRMED);
          c.setUpdatedAt(OffsetDateTime.now());
          cycles.save(c);
        }
        b.setNextDueOn(advance(b));
        moved = true;
      }
      if (moved) {
        b.setUpdatedAt(OffsetDateTime.now());
        bills.save(b);
      }
      ensureCycle(b, b.getNextDueOn(), b.getStatus() == BillStatus.MANUAL);
      return;
    }
    BiBillCycle c = ensureCycle(b, b.getNextDueOn(), true);
    if (c.getStatus() == BillCycleStatus.DUE && today.isAfter(c.getDueOn().plusDays(ASK_DAYS_AFTER_DUE))) {
      c.setStatus(BillCycleStatus.NOT_CONFIRMED);
      c.setUpdatedAt(OffsetDateTime.now());
      cycles.save(c);
    }
  }

  /**
   * A payment of this bill less than most of a cycle before {@code paidOn}: a second "Paid" (from
   * another phone, or tapped twice) is the same cycle, not next month's rent paid early.
   */
  static boolean paidRecently(BiUserBill bill, LocalDate paidOn) {
    if (bill.getLastPaidOn() == null) {
      return false;
    }
    int minGap =
        switch (bill.getCadence()) {
          case WEEKLY -> 4;
          case MONTHLY -> 20;
          case QUARTERLY -> 60;
          case YEARLY -> 300;
        };
    return ChronoUnit.DAYS.between(bill.getLastPaidOn(), paidOn) < minGap;
  }

  /** Paid on, or no more than a week before, the date it is due. */
  static boolean paidThisCycle(BiUserBill bill) {
    return bill.getLastPaidOn() != null
        && !bill.getLastPaidOn().isBefore(bill.getNextDueOn().minusDays(PAID_EARLY_DAYS));
  }

  /**
   * Bill identification (V1.27.0): on unless switched off, and when it is a Premium feature, only
   * while the Premium gate is open. Off, detection still runs and keeps its suggestions out of
   * sight, so they appear at once when the feature is opened.
   */
  private boolean suggestionsOn() {
    InsightsProperties.Bills config = properties.getBills();
    return config.isSuggestions() && (!config.isSuggestionsPremium() || premiumFeature());
  }

  /**
   * Bill categories (V1.24.0): on unless switched off, and when they are a Premium feature, only
   * while the Premium gate is open.
   */
  private boolean categoriesOn() {
    InsightsProperties.Bills config = properties.getBills();
    return config.isCategories() && (!config.isCategoriesPremium() || premiumFeature());
  }

  /** A category the customer picked for a bill: an active one, and only while categories are on. */
  private UUID chosenCategory(UUID categoryId) {
    if (categoryId == null || !categoriesOn()) {
      return null;
    }
    return categories
        .findById(categoryId)
        .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
        .map(c -> c.getId())
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_INVALID));
  }

  /**
   * A bill paid by hand is categorised like its kind: the category for the biller's type (V1.31.0:
   * electricity and water, mobile, subscriptions…), else Bills & utilities, then Electricity &
   * water, else Other — the first one that is active.
   */
  private UUID billCategory(BiUserBill bill) {
    String typed = null;
    if (bill.getBillerId() != null) {
      typed = billers.findById(bill.getBillerId()).map(b -> categoryFor(b.getBillType())).orElse(null);
    }
    List<String> codes = new ArrayList<>();
    if (typed != null) {
      codes.add(typed);
    }
    codes.addAll(List.of("BILLS_UTILITIES", "UTILITIES", BiCategory.OTHER_CODE));
    for (String code : codes) {
      Optional<BiCategory> found = categories.findByCodeAndIsActiveTrue(code);
      if (found.isPresent()) {
        return found.get().getId();
      }
    }
    return null;
  }

  static String categoryFor(com.moneyreserve.domain.BillType type) {
    if (type == null) {
      return null;
    }
    return switch (type) {
      case ELECTRICITY, WATER -> "UTILITIES";
      case TELECOM -> "MOBILE_DATA";
      case STREAMING -> "SUBSCRIPTIONS";
      case INSURANCE -> "INSURANCE";
      case LOAN, CARD -> "DEBT_PAYMENTS";
      case RENT -> "RENT_HOUSING";
      case OTHER -> null;
    };
  }

  @Override
  @Transactional
  public void refresh(UUID userId, LocalDate today) {
    InsightsProperties.Bills config = properties.getBills();
    List<Payment> payments = payments(userId);
    List<KnownBiller> knownBillers = known();
    List<Found> found = detector.detect(payments, knownBillers, today);
    // "Missed?" only once the customer's payments are known to reach past the due date: while a
    // first import is still arriving, a gap is not yet news.
    LocalDate latestPayment = payments.stream().map(Payment::date).max(LocalDate::compareTo).orElse(null);
    Map<String, BiUserBill> existing = new HashMap<>();
    // Locked (V1.26.0): the cycles' rollover and a bill being marked paid never run at once.
    for (BiUserBill b : bills.lockAllByUserId(userId)) {
      existing.put(b.getPayeeKey(), b);
    }
    OffsetDateTime now = OffsetDateTime.now();
    Set<String> seen = new HashSet<>();
    for (Found f : found) {
      seen.add(f.payeeKey());
      BiUserBill b = existing.get(f.payeeKey());
      if (b != null && (b.getStatus() == BillStatus.DISMISSED || b.getStatus() == BillStatus.MANUAL)) {
        continue;
      }
      boolean newPayment = b == null || !Objects.equals(b.getLastPaidOn(), f.lastPaidOn());
      LocalDate previousDue = b == null ? null : b.getNextDueOn();
      if (b != null && b.isUserSchedule()) {
        // Tracked by the customer (V1.27.0): their amount, repeat and due date stand. Detection
        // only notes the latest payment, which settles the cycle it covers.
        b.setLastAmount(f.lastAmount());
        b.setLastPaidOn(f.lastPaidOn());
        b.setPaymentCount((short) Math.min(f.paymentCount(), Short.MAX_VALUE));
        b.setUpdatedAt(now);
        b = bills.save(b);
        existing.put(b.getPayeeKey(), b);
        continue; // its cycles are settled from its payee's payments below
      }
      if (b == null && !suggestable(f)) {
        continue; // not clear enough to suggest
      }
      if (b == null) {
        // New finds are suggestions (V1.27.0): never a bill until the customer tracks one.
        b =
            BiUserBill.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .payeeKey(f.payeeKey())
                .status(BillStatus.SUGGESTED)
                .remindDaysBefore(config.getDefaultRemindDays())
                .createdAt(now)
                .build();
      }
      if (b.getStatus() == BillStatus.SUGGESTED) {
        b.setEvidence(evidence(f));
      }
      b.setBillerId(f.biller() == null ? null : f.biller().id());
      b.setDetectedName(clip(f.name(), 128));
      b.setCadence(f.cadence());
      b.setExpectedAmount(f.expectedAmount());
      b.setLastAmount(f.lastAmount());
      b.setLastPaidOn(f.lastPaidOn());
      b.setNextDueOn(f.nextDueOn());
      b.setAnchorDay(f.anchorDay() == null ? null : f.anchorDay().shortValue());
      b.setPaymentCount((short) Math.min(f.paymentCount(), Short.MAX_VALUE));
      b.setUpdatedAt(now);
      b = bills.save(b);
      existing.put(b.getPayeeKey(), b);
      if (b.getStatus() == BillStatus.SUGGESTED) {
        continue; // no cycles, alerts or questions for a suggestion
      }
      syncFoundCycles(b, previousDue, newPayment, today, lastId(f));
      if (newPayment) {
        withdrawCoveredMissed(b);
      }
      if (newPayment && jumped(f, config) && premiumFeature() && recent(f.lastPaidOn(), today)) {
        raise(b, f.lastPaidOn(), BudgetAlertType.BILL_JUMPED, f.lastAmount(), f.previousAverage());
      }
    }
    for (BiUserBill b : existing.values()) {
      if ((b.getStatus() == BillStatus.DETECTED || b.getStatus() == BillStatus.SUGGESTED)
          && !seen.contains(b.getPayeeKey())) {
        cycles.deleteAllForBill(b.getId());
        bills.delete(b); // no longer looks recurring and was never confirmed
        continue;
      }
      if (b.getStatus() == BillStatus.DISMISSED || b.getStatus() == BillStatus.SUGGESTED) {
        continue;
      }
      if (b.isUserSchedule()) {
        settleFromPayments(b, payments, knownBillers);
      }
      // A bill is asked about from its due date for a few days ("Did you pay it?"); marking it
      // paid moves a bill added by hand on at once (markPaid). Unanswered, the cycle becomes
      // "Payment not confirmed" and a bill added by hand moves on by itself.
      rollOver(b, today);
      long days = ChronoUnit.DAYS.between(today, b.getNextDueOn());
      short remind = premiumFeature() ? b.getRemindDaysBefore() : 1;
      if (days >= 0 && days <= remind) {
        raise(b, b.getNextDueOn(), BudgetAlertType.BILL_DUE, b.getExpectedAmount(), b.getExpectedAmount());
      }
      if (!ownsSchedule(b)
          && -days >= config.getMissedAfterDays()
          && latestPayment != null
          && !latestPayment.isBefore(b.getNextDueOn().plusDays(config.getMissedAfterDays()))
          && premiumFeature()) {
        raise(b, b.getNextDueOn(), BudgetAlertType.BILL_MISSED, b.getExpectedAmount(), b.getExpectedAmount());
      }
    }
    log.debug("Insights bills refreshed userId={} found={}", userId, found.size());
  }

  @Override
  public void refreshAfterCommit(UUID userId) {
    if (!properties.getBills().isEnabled() || userId == null) {
      return;
    }
    Runnable run =
        () -> {
          try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> refresh(userId, today()));
          } catch (RuntimeException e) {
            log.error("Insights bills refresh failed; the payment itself was saved", e);
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              run.run();
            }
          });
    } else {
      run.run();
    }
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private BillsResponse response(UUID userId) {
    Map<UUID, BiBiller> byId = billerById();
    LocalDate today = today();
    List<BillDto> out = new ArrayList<>();
    BigDecimal monthly = BigDecimal.ZERO;
    Map<UUID, List<BiBillCycle>> byBill = new HashMap<>();
    for (BiBillCycle c : cycles.findAllByUserIdAndDueOnGreaterThanEqual(userId, LocalDate.of(2000, 1, 1))) {
      byBill.computeIfAbsent(c.getUserBillId(), k -> new ArrayList<>()).add(c);
    }
    List<BillDto> suggested = new ArrayList<>();
    for (BiUserBill b : bills.findAllByUserIdOrderByNextDueOnAsc(userId)) {
      if (b.getStatus() == BillStatus.DISMISSED) {
        continue;
      }
      if (b.getStatus() == BillStatus.SUGGESTED) {
        // Not a bill yet: listed apart, never in the bills, the total or any reminder.
        suggested.add(toDto(b, byId, today, List.of()));
        continue;
      }
      out.add(toDto(b, byId, today, byBill.getOrDefault(b.getId(), List.of())));
      monthly = monthly.add(perMonth(b));
    }
    return BillsResponse.builder()
        .bills(out)
        .monthlyTotal(mapper.money(monthly.setScale(2, RoundingMode.HALF_UP)))
        .currency(properties.getCurrency())
        .premiumGate(properties.getBills().isPremiumGate())
        .categoriesEnabled(categoriesOn())
        .suggestionsEnabled(suggestionsOn())
        .suggestions(suggestionsOn() ? suggested : List.of())
        .build();
  }

  private static BigDecimal perMonth(BiUserBill b) {
    BigDecimal amount = b.getExpectedAmount();
    return switch (b.getCadence()) {
      case WEEKLY -> amount.multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
      case MONTHLY -> amount;
      case QUARTERLY -> amount.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
      case YEARLY -> amount.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    };
  }

  private BillDto toDto(BiUserBill b, Map<UUID, BiBiller> byId, LocalDate today, List<BiBillCycle> own) {
    BiBillCycle current = own.stream().filter(c -> c.getDueOn().equals(b.getNextDueOn())).findFirst().orElse(null);
    long unconfirmed = own.stream().filter(c -> c.getStatus() == BillCycleStatus.NOT_CONFIRMED).count();
    BiBiller biller = b.getBillerId() == null ? null : byId.get(b.getBillerId());
    return BillDto.builder()
        .id(b.getId())
        .name(b.displayName())
        .detectedName(b.getDetectedName())
        .status(b.getStatus().name())
        .cadence(b.getCadence().name())
        .expectedAmount(mapper.money(b.getExpectedAmount()))
        .lastAmount(b.getLastAmount() == null ? null : mapper.money(b.getLastAmount()))
        .lastPaidOn(b.getLastPaidOn())
        .nextDueOn(b.getNextDueOn())
        .daysUntilDue(ChronoUnit.DAYS.between(today, b.getNextDueOn()))
        .remindDaysBefore(b.getRemindDaysBefore())
        .paymentCount((int) b.getPaymentCount())
        .billerCode(biller == null ? null : biller.getCode())
        .billerNameSi(biller == null ? null : biller.getNameSi())
        .billerNameTa(biller == null ? null : biller.getNameTa())
        .billType(biller == null ? null : biller.getBillType().name())
        .iconKey(biller == null ? null : biller.getIconKey())
        .colourHex(biller == null ? null : biller.getColourHex())
        .currency(properties.getCurrency())
        // The current cycle's own answer; before it exists, the rule used before cycles.
        .paidThisCycle(current != null ? current.getStatus() == BillCycleStatus.PAID : paidThisCycle(b))
        .unconfirmedCycles(unconfirmed == 0 ? null : (int) unconfirmed)
        .anchorDay(b.getAnchorDay() == null ? null : (int) b.getAnchorDay())
        .tracked(b.isUserSchedule() ? Boolean.TRUE : null)
        .categoryId(categoriesOn() ? b.getCategoryId() : null)
        .build();
  }

  private void raise(BiUserBill b, LocalDate on, BudgetAlertType type, BigDecimal amount, BigDecimal limit) {
    if (!properties.getAlerts().isEnabled()
        || alerts.existsByBillIdAndPeriodStartAndAlertType(b.getId(), on, type)) {
      return;
    }
    alerts.save(
        BiBudgetAlert.builder()
            .userId(b.getUserId())
            .billId(b.getId())
            .periodStart(on)
            .alertType(type)
            .amountAtRaise(amount == null ? BigDecimal.ZERO : amount)
            .limitAtRaise(limit == null ? BigDecimal.ZERO : limit)
            .raisedAt(OffsetDateTime.now())
            .build());
    log.info("Insights bill alert raised userId={} billId={} type={} on={}", b.getUserId(), b.getId(), type, on);
  }

  /**
   * A "missed?" the customer has not seen yet is withdrawn once a payment turns up for that due
   * date (paid late, or payments that arrived out of date order): it was not missed after all.
   */
  private void withdrawCoveredMissed(BiUserBill b) {
    if (b.getLastPaidOn() == null) {
      return;
    }
    for (BiBudgetAlert alert : alerts.findAllByBillIdAndAlertType(b.getId(), BudgetAlertType.BILL_MISSED)) {
      boolean covered = !b.getLastPaidOn().isBefore(alert.getPeriodStart().minusDays(7));
      if (covered && !alert.isAcknowledged()) {
        alerts.delete(alert);
        log.info("Insights bill alert withdrawn billId={} due={}: the payment came", b.getId(), alert.getPeriodStart());
      }
    }
  }

  private static boolean jumped(Found f, InsightsProperties.Bills config) {
    if (f.previousAverage() == null || f.previousAverage().signum() <= 0) {
      return false;
    }
    BigDecimal threshold =
        f.previousAverage().multiply(BigDecimal.valueOf(100L + config.getJumpPct())).divide(BigDecimal.valueOf(100));
    return f.lastAmount().compareTo(threshold) >= 0;
  }

  /** A jump is news only about a recent payment, not one found months later. */
  private static boolean recent(LocalDate paidOn, LocalDate today) {
    return paidOn != null && ChronoUnit.DAYS.between(paidOn, today) <= 35;
  }

  /** Premium-only features are open to everyone until the gate is switched on. */
  private boolean premiumFeature() {
    return !properties.getBills().isPremiumGate();
  }

  private short remindDays(Short requested) {
    InsightsProperties.Bills config = properties.getBills();
    if (requested == null) {
      return config.getDefaultRemindDays();
    }
    if (!premiumFeature() && requested != 1) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_PREMIUM);
    }
    return requested;
  }

  private static LocalDate advance(BiUserBill b) {
    if (b.getCadence() == BillCadence.WEEKLY) {
      return b.getNextDueOn().plusDays(7);
    }
    int anchor = b.getAnchorDay() != null ? b.getAnchorDay() : b.getNextDueOn().getDayOfMonth();
    return BillDetector.nextDue(b.getNextDueOn(), b.getCadence(), anchor);
  }

  private BiUserBill owned(Caller caller, UUID billId) {
    return bills
        .findByIdAndUserId(billId, caller.userId())
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND));
  }

  private List<Payment> payments(UUID userId) {
    LocalDate today = today();
    LocalDate from = today.minusMonths(Math.max(1, properties.getLookbackMonths()));
    UUID transfers = categories.findByCodeAndIsActiveTrue(TRANSFERS).map(c -> c.getId()).orElse(null);
    UUID cashWithdrawal =
        categories.findByCodeAndIsActiveTrue(BiCategory.CASH_WITHDRAWAL_CODE).map(c -> c.getId()).orElse(null);
    Map<UUID, String> codes = new HashMap<>();
    for (BiCategory c : categories.findAll()) {
      codes.put(c.getId(), c.getCode());
    }
    List<Payment> out = new ArrayList<>();
    for (BiTransaction t :
        transactions.findAllByUserIdAndTxnDateBetweenAndDeletedAtIsNullOrderByTxnDateDesc(userId, from, today)) {
      // Cash taken out at an ATM and confirmed own-money movements are never a bill payment.
      if (t.getMovement() != null || (cashWithdrawal != null && cashWithdrawal.equals(t.getCategoryId()))) {
        continue;
      }
      out.add(
          new Payment(
              t.getTxnDate(),
              t.getAmount(),
              t.getDirection() == TxnDirection.CREDIT,
              t.getMerchant(),
              transfers != null && transfers.equals(t.getCategoryId()),
              t.getId(),
              t.getAccountId(),
              t.getCategoryId() == null ? null : codes.get(t.getCategoryId())));
    }
    return out;
  }

  private List<KnownBiller> known() {
    List<KnownBiller> out = new ArrayList<>();
    for (BiBiller b : billers.findAllByIsActiveTrueOrderBySortOrderAsc()) {
      out.add(
          new KnownBiller(
              b.getId(), b.getCode(), b.getNameEn(), b.getBillType(), b.tolerancePct(), MerchantTerms.parse(b.getMatchTerms())));
    }
    return out;
  }

  private Map<UUID, BiBiller> billerById() {
    Map<UUID, BiBiller> out = new HashMap<>();
    for (BiBiller b : billers.findAll()) {
      out.put(b.getId(), b);
    }
    return out;
  }

  private LocalDate today() {
    return LocalDate.now(properties.zoneId());
  }

  private static String clip(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
