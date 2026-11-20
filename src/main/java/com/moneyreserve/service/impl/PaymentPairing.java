package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.calc.BillDetector;
import com.moneyreserve.service.calc.BillDetector.KnownBiller;
import com.moneyreserve.service.calc.BillDetector.Payment;
import com.moneyreserve.service.calc.MerchantTerms;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The one place that decides whether a bank SMS and a payment the customer recorded themselves are
 * the same payment (V1.23.0, verified links since V1.26.0).
 *
 * <p>A customer records a payment when their bank only sent an "attempted … use code" message and
 * they confirm it went through, or when they mark a bill paid. Some banks then send an SMS for the
 * same payment, some never do. They are one payment only when the link is verified, never on the
 * amount alone:
 *
 * <ul>
 *   <li>Money out, the exact same amount, dates at most {@link #WINDOW_DAYS} apart; and
 *   <li>the same bank, known on both sides, or the SMS's payee is the bill's own payee (the biller
 *       or merchant the bill was found from); and
 *   <li>no other candidate either way: when two recorded payments could be that SMS (or two SMS
 *       that payment), nothing is paired and both stay as they are, for the customer to sort out,
 *       rather than a guess.
 * </ul>
 *
 * Pairing keeps the earlier row and records the other side's key on it ({@code linked_key}), so a
 * resent SMS or a retried confirmation stays a duplicate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentPairing {

  /** How far apart a bank SMS and a recorded payment may be dated and still be one payment. */
  public static final int WINDOW_DAYS = 2;

  public static final String ATTEMPT = "ATTEMPT";
  public static final String BILL = "BILL";

  private final BiTransactionRepository transactions;
  private final BiAccountRepository accounts;
  private final BiBankParserTemplateRepository templates;
  private final BiUserBillRepository bills;
  private final BiBillerRepository billers;

  /** At ingest: the recorded payment a bank SMS debit belongs to, if the link is verified. */
  public Optional<BiTransaction> recordedFor(
      UUID userId, String bankCode, String merchant, BigDecimal amount, LocalDate date, Set<UUID> pairedAlready) {
    List<KnownBiller> known = known();
    List<BiTransaction> candidates =
        transactions
            .findUnpairedRecorded(userId, amount, date.minusDays(WINDOW_DAYS), date.plusDays(WINDOW_DAYS))
            .stream()
            .filter(t -> !pairedAlready.contains(t.getId()))
            .filter(t -> verified(t, bankCode, merchant, date, known))
            .toList();
    if (candidates.size() != 1) {
      if (candidates.size() > 1) {
        log.info("Insights SMS not paired: {} recorded payments could be it userId={}", candidates.size(), userId);
      }
      return Optional.empty();
    }
    BiTransaction recorded = candidates.get(0);
    // Another stored SMS that could equally be this recorded payment: not a verified one-to-one.
    boolean rival =
        transactions
            .findUnpairedSms(
                userId, amount, recorded.getTxnDate().minusDays(WINDOW_DAYS), recorded.getTxnDate().plusDays(WINDOW_DAYS))
            .stream()
            .anyMatch(s -> verified(recorded, bankOf(s), s.getMerchant(), s.getTxnDate(), known));
    if (rival) {
      log.info("Insights SMS not paired: another stored SMS could be the same payment userId={}", userId);
      return Optional.empty();
    }
    return Optional.of(recorded);
  }

  /**
   * When a payment is recorded: the bank SMS already stored for it, if the link is verified.
   * {@code bankCode} is the bank the customer paid from, when known; {@code billId} the bill paid.
   */
  public Optional<BiTransaction> smsFor(
      UUID userId, String bankCode, UUID billId, BigDecimal amount, LocalDate date) {
    List<KnownBiller> known = known();
    String billPayee = billPayee(billId);
    List<BiTransaction> candidates =
        transactions
            .findUnpairedSms(userId, amount, date.minusDays(WINDOW_DAYS), date.plusDays(WINDOW_DAYS))
            .stream()
            .filter(s -> linked(bankCode, billPayee, bankOf(s), s.getMerchant(), s.getTxnDate(), known))
            .toList();
    if (candidates.size() != 1) {
      if (candidates.size() > 1) {
        log.info("Insights payment not paired: {} stored SMS could be it userId={}", candidates.size(), userId);
      }
      return Optional.empty();
    }
    BiTransaction sms = candidates.get(0);
    // An earlier recorded payment that could equally be this SMS: not a verified one-to-one.
    boolean rival =
        transactions
            .findUnpairedRecorded(userId, amount, sms.getTxnDate().minusDays(WINDOW_DAYS), sms.getTxnDate().plusDays(WINDOW_DAYS))
            .stream()
            .anyMatch(r -> verified(r, bankOf(sms), sms.getMerchant(), sms.getTxnDate(), known));
    if (rival) {
      log.info("Insights payment not paired: another recorded payment could be that SMS userId={}", userId);
      return Optional.empty();
    }
    return Optional.of(sms);
  }

  // ---- Bills the customer said they paid (V1.26.0) ------------------------------------------

  /** A bank SMS this many days either side of a confirmed bill payment can be that payment. */
  public static final int BILL_WINDOW_DAYS = 3;

  /** Beyond the bill's own tolerance, but within this much of it: ask, never link. */
  static final int ASK_PCT = 40;

  /**
   * A bank SMS and a bill payment the customer confirmed. {@code linked}: certain (the only
   * candidate both ways, the amount within the bill's tolerance, no conflicting bank), so it is made
   * that payment; otherwise the customer is asked once.
   */
  public record BillMatch(BiTransaction recorded, BiTransaction sms, boolean linked) {}

  private enum Fit { NONE, ASK, LINK }

  /**
   * At ingest: the bill payment the customer confirmed that this SMS fits, if any. The customer
   * already said they paid that bill, which is the link; amount, date and bank only have to agree.
   */
  public Optional<BillMatch> billPaymentFor(
      UUID userId, String smsBank, BigDecimal amount, LocalDate date, Set<UUID> pairedAlready) {
    List<BiTransaction> candidates =
        transactions.findUnpairedBillPayments(userId, date.minusDays(BILL_WINDOW_DAYS), date.plusDays(BILL_WINDOW_DAYS)).stream()
            .filter(r -> !pairedAlready.contains(r.getId()))
            .filter(r -> sameBankOrUnknown(r.getOriginBankCode(), smsBank))
            .filter(r -> fit(r.getUserBillId(), r.getAmount(), amount) != Fit.NONE)
            .sorted(Comparator.comparingLong((BiTransaction r) -> Math.abs(ChronoUnit.DAYS.between(r.getTxnDate(), date))))
            .toList();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    BiTransaction recorded = candidates.get(0);
    boolean certain =
        candidates.size() == 1
            && fit(recorded.getUserBillId(), recorded.getAmount(), amount) == Fit.LINK
            && transactions
                .findUnpairedSmsBetween(
                    userId, recorded.getTxnDate().minusDays(BILL_WINDOW_DAYS), recorded.getTxnDate().plusDays(BILL_WINDOW_DAYS))
                .stream()
                .noneMatch(
                    s -> sameBankOrUnknown(recorded.getOriginBankCode(), bankOf(s))
                        && fit(recorded.getUserBillId(), recorded.getAmount(), s.getAmount()) != Fit.NONE);
    return Optional.of(new BillMatch(recorded, null, certain));
  }

  /**
   * When the customer confirms a bill payment: the bank SMS already stored that fits it, if any
   * ({@code linked} when certain, so it becomes the payment; otherwise one to ask about).
   */
  public Optional<BillMatch> smsForBill(
      UUID userId, String bankCode, UUID billId, BigDecimal amount, LocalDate date, UUID exceptRecorded) {
    List<BiTransaction> candidates =
        transactions.findUnpairedSmsBetween(userId, date.minusDays(BILL_WINDOW_DAYS), date.plusDays(BILL_WINDOW_DAYS)).stream()
            .filter(s -> sameBankOrUnknown(bankCode, bankOf(s)))
            .filter(s -> fit(billId, amount, s.getAmount()) != Fit.NONE)
            .sorted(Comparator.comparingLong((BiTransaction s) -> Math.abs(ChronoUnit.DAYS.between(s.getTxnDate(), date))))
            .toList();
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    BiTransaction sms = candidates.get(0);
    boolean certain =
        candidates.size() == 1
            && fit(billId, amount, sms.getAmount()) == Fit.LINK
            && transactions
                .findUnpairedBillPayments(
                    userId, sms.getTxnDate().minusDays(BILL_WINDOW_DAYS), sms.getTxnDate().plusDays(BILL_WINDOW_DAYS))
                .stream()
                .filter(r -> !r.getId().equals(exceptRecorded))
                .noneMatch(
                    r -> sameBankOrUnknown(r.getOriginBankCode(), bankOf(sms))
                        && fit(r.getUserBillId(), r.getAmount(), sms.getAmount()) != Fit.NONE);
    return Optional.of(new BillMatch(null, sms, certain));
  }

  /** Could this SMS amount be that bill payment? (For "Yes, this is my … payment".) */
  public boolean couldBe(UUID billId, BigDecimal recordedAmount, BigDecimal smsAmount) {
    return fit(billId, recordedAmount, smsAmount) != Fit.NONE;
  }

  /** Both banks known and different: not the same payment. Either unknown: no objection. */
  private static boolean sameBankOrUnknown(String a, String b) {
    return a == null || b == null || a.equals(b);
  }

  /**
   * The same amount links. A found bill's biller allows its own tolerance (a water or electricity
   * bill changes month to month); a bill added by hand links only on the same amount. Within
   * {@link #ASK_PCT}% it is a question, beyond that it is a different payment.
   */
  private Fit fit(UUID billId, BigDecimal recorded, BigDecimal sms) {
    if (recorded == null || sms == null || recorded.signum() <= 0) {
      return Fit.NONE;
    }
    BigDecimal diff = sms.subtract(recorded).abs();
    if (diff.compareTo(new BigDecimal("0.005")) < 0) {
      return Fit.LINK;
    }
    int tolerance = billTolerance(billId);
    if (tolerance > 0 && diff.multiply(BigDecimal.valueOf(100)).compareTo(recorded.multiply(BigDecimal.valueOf(tolerance))) <= 0) {
      return Fit.LINK;
    }
    return diff.multiply(BigDecimal.valueOf(100)).compareTo(recorded.multiply(BigDecimal.valueOf(ASK_PCT))) <= 0
        ? Fit.ASK
        : Fit.NONE;
  }

  /** The biller's amount tolerance (%) for a bill found from a biller; 0 for any other bill. */
  private int billTolerance(UUID billId) {
    if (billId == null) {
      return 0;
    }
    return bills
        .findById(billId)
        .filter(b -> b.getBillerId() != null)
        .flatMap(b -> billers.findById(b.getBillerId()))
        .map(b -> (int) b.tolerancePct())
        .orElse(0);
  }

  /** A recorded payment and an SMS (its bank and payee): a verified link? */
  private boolean verified(BiTransaction recorded, String smsBank, String smsMerchant, LocalDate smsDate, List<KnownBiller> known) {
    return linked(recorded.getOriginBankCode(), billPayee(recorded.getUserBillId()), smsBank, smsMerchant, smsDate, known);
  }

  /**
   * The same bank, known on both sides; or the SMS's payee is the bill's payee. Anything less
   * (an unknown bank on either side, a bill added by hand with no payee) is not verified.
   */
  private static boolean linked(
      String paidFromBank, String billPayee, String smsBank, String smsMerchant, LocalDate smsDate, List<KnownBiller> known) {
    if (paidFromBank != null) {
      return paidFromBank.equals(smsBank);
    }
    if (billPayee == null || smsMerchant == null) {
      return false;
    }
    return BillDetector.payeeKey(new Payment(smsDate, BigDecimal.ONE, false, smsMerchant, false), known)
        .map(billPayee::equals)
        .orElse(false);
  }

  /** The payee a bill was found from (B:… or M:…); none for one added by hand. */
  private String billPayee(UUID billId) {
    if (billId == null) {
      return null;
    }
    return bills
        .findById(billId)
        .map(b -> b.getPayeeKey())
        .filter(k -> k.startsWith("B:") || k.startsWith("M:"))
        .orElse(null);
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
  /** The bank an SMS transaction came from: its account's bank, or its parser template's. */
  String bankOf(BiTransaction t) {
    if (t.getOriginBankCode() != null) {
      return t.getOriginBankCode();
    }
    if (t.getAccountId() != null) {
      String fromAccount = accounts.findById(t.getAccountId()).map(a -> a.getBankCode()).orElse(null);
      if (fromAccount != null) {
        return fromAccount;
      }
    }
    if (t.getParserTemplateId() != null) {
      return templates.findById(t.getParserTemplateId()).map(x -> x.getBankCode()).orElse(null);
    }
    return null;
  }
}
