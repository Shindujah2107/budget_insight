package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiBudgetAlert;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
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
  private final BiBillerRepository billers;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiBudgetAlertRepository alerts;
  private final InsightsMapper mapper;
  private final InsightsProperties properties;
  private final PlatformTransactionManager transactionManager;
  private final BillDetector detector = new BillDetector();

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
    return BillDetailResponse.builder().bill(toDto(bill, billerById(), today())).payments(paid).build();
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
    log.info("Insights bill added by hand userId={} billId={}", caller.userId(), id);
    return toDto(bill, billerById(), today());
  }

  @Override
  @Transactional
  public BillDto update(Caller caller, UUID billId, UpdateBillRequest request) {
    BiUserBill bill = owned(caller, billId);
    boolean manual = bill.getStatus() == BillStatus.MANUAL;
    if (!manual
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
    if (manual) {
      if (request.expectedAmount() != null) {
        bill.setExpectedAmount(request.expectedAmount().setScale(2, RoundingMode.HALF_UP));
      }
      if (request.cadence() != null) {
        bill.setCadence(BillCadence.valueOf(request.cadence()));
      }
      if (request.nextDueOn() != null) {
        LocalDate due = request.nextDueOn();
        bill.setNextDueOn(due);
        bill.setAnchorDay((short) (due.getDayOfMonth() == due.lengthOfMonth() ? 31 : due.getDayOfMonth()));
      }
    }
    bill.setUpdatedAt(OffsetDateTime.now());
    bill = bills.save(bill);
    log.info("Insights bill updated userId={} billId={} status={}", caller.userId(), billId, bill.getStatus());
    return toDto(bill, billerById(), today());
  }

  @Override
  @Transactional
  public BillsResponse delete(Caller caller, UUID billId) {
    BiUserBill bill = owned(caller, billId);
    if (bill.getStatus() == BillStatus.MANUAL) {
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
    BiUserBill bill = owned(caller, billId);
    if (bill.getStatus() == BillStatus.DISMISSED) {
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_BILL_NOT_FOUND);
    }
    LocalDate paidOn = request.paidOn() == null ? today() : request.paidOn();
    if (paidThisCycle(bill) || paidRecently(bill, paidOn)) {
      log.info("Insights bill already paid this cycle userId={} billId={}", caller.userId(), billId);
      return com.moneyreserve.dto.BillPaidResponse.builder()
          .alreadyPaid(Boolean.TRUE)
          .bill(toDto(bill, billerById(), today()))
          .build();
    }
    BigDecimal amount =
        (request.amount() == null ? bill.getExpectedAmount() : request.amount()).setScale(2, RoundingMode.HALF_UP);
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
                    : categoriesOn() && bill.getCategoryId() != null ? bill.getCategoryId() : billCategory())
            .clientRef(request.clientRef())
            .origin(request.originBankCode() != null ? PaymentPairing.ATTEMPT : PaymentPairing.BILL)
            .originBankCode(request.originBankCode())
            .userBillId(bill.getId())
            .build();
    com.moneyreserve.dto.TransactionMutationResponse recorded =
        transactionService.getObject().create(caller, payment, null, null);
    if (bill.getStatus() == BillStatus.MANUAL) {
      bill.setLastPaidOn(paidOn);
      bill.setLastAmount(amount);
      bill.setPaymentCount((short) Math.min(bill.getPaymentCount() + 1, Short.MAX_VALUE));
      while (!bill.getNextDueOn().isAfter(paidOn.plusDays(PAID_EARLY_DAYS))) {
        bill.setNextDueOn(advance(bill));
      }
      bill.setUpdatedAt(OffsetDateTime.now());
      bill = bills.save(bill);
    }
    refreshAfterCommit(caller.userId());
    log.info("Insights bill marked paid userId={} billId={}", caller.userId(), billId);
    return com.moneyreserve.dto.BillPaidResponse.builder()
        .payment(recorded)
        .bill(toDto(bill, billerById(), today()))
        .build();
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

  /** Bills & utilities, else Other: a bill paid by hand is categorised like its kind. */
  private UUID billCategory() {
    return categories
        .findByCodeAndIsActiveTrue("BILLS_UTILITIES")
        .or(() -> categories.findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE))
        .map(c -> c.getId())
        .orElse(null);
  }

  @Override
  @Transactional
  public void refresh(UUID userId, LocalDate today) {
    InsightsProperties.Bills config = properties.getBills();
    List<Payment> payments = payments(userId);
    List<Found> found = detector.detect(payments, known(), today);
    // "Missed?" only once the customer's payments are known to reach past the due date: while a
    // first import is still arriving, a gap is not yet news.
    LocalDate latestPayment = payments.stream().map(Payment::date).max(LocalDate::compareTo).orElse(null);
    Map<String, BiUserBill> existing = new HashMap<>();
    for (BiUserBill b : bills.findAllByUserIdOrderByNextDueOnAsc(userId)) {
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
      if (b == null) {
        b =
            BiUserBill.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .payeeKey(f.payeeKey())
                .status(BillStatus.DETECTED)
                .remindDaysBefore(config.getDefaultRemindDays())
                .createdAt(now)
                .build();
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
      if (newPayment) {
        withdrawCoveredMissed(b);
      }
      if (newPayment && jumped(f, config) && premiumFeature() && recent(f.lastPaidOn(), today)) {
        raise(b, f.lastPaidOn(), BudgetAlertType.BILL_JUMPED, f.lastAmount(), f.previousAverage());
      }
    }
    for (BiUserBill b : existing.values()) {
      if (b.getStatus() == BillStatus.DETECTED && !seen.contains(b.getPayeeKey())) {
        bills.delete(b); // no longer looks recurring and was never confirmed
        continue;
      }
      // A bill added by hand stays on its due date for a few days so the app can ask "Did you pay
      // it?"; marking it paid moves it on at once (markPaid). Unanswered, it moves on by itself.
      if (b.getStatus() == BillStatus.MANUAL
          && today.isAfter(b.getNextDueOn().plusDays(ASK_DAYS_AFTER_DUE))) {
        while (today.isAfter(b.getNextDueOn())) {
          b.setNextDueOn(advance(b));
        }
        b.setUpdatedAt(now);
        bills.save(b);
      }
      if (b.getStatus() == BillStatus.DISMISSED) {
        continue;
      }
      long days = ChronoUnit.DAYS.between(today, b.getNextDueOn());
      short remind = premiumFeature() ? b.getRemindDaysBefore() : 1;
      if (days >= 0 && days <= remind) {
        raise(b, b.getNextDueOn(), BudgetAlertType.BILL_DUE, b.getExpectedAmount(), b.getExpectedAmount());
      }
      if (b.getStatus() != BillStatus.MANUAL
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
    for (BiUserBill b : bills.findAllByUserIdOrderByNextDueOnAsc(userId)) {
      if (b.getStatus() == BillStatus.DISMISSED) {
        continue;
      }
      out.add(toDto(b, byId, today));
      monthly = monthly.add(perMonth(b));
    }
    return BillsResponse.builder()
        .bills(out)
        .monthlyTotal(mapper.money(monthly.setScale(2, RoundingMode.HALF_UP)))
        .currency(properties.getCurrency())
        .premiumGate(properties.getBills().isPremiumGate())
        .categoriesEnabled(categoriesOn())
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

  private BillDto toDto(BiUserBill b, Map<UUID, BiBiller> byId, LocalDate today) {
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
        .paidThisCycle(paidThisCycle(b))
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
              transfers != null && transfers.equals(t.getCategoryId())));
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
