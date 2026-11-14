package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that decides whether a bank SMS and a payment the customer recorded themselves are
 * the same payment (V1.23.0).
 *
 * <p>A customer records a payment when their bank only sent an "attempted … use code" message and
 * they confirm it went through, or when they mark a bill paid. Some banks then send an SMS for the
 * same payment, some never do. Rules:
 *
 * <ul>
 *   <li>Same direction (money out), the exact same amount, dates at most {@link #WINDOW_DAYS} apart.
 *   <li>Same bank when both sides know it; a bill marked paid matches any bank.
 *   <li>One to one: an SMS pairs with at most one recorded payment and the reverse, oldest first.
 *       Two payments of the same amount stay two unless both sides of both pairs exist.
 * </ul>
 *
 * Pairing keeps the earlier row and records the other side's key on it ({@code linked_key}), so a
 * resent SMS or a retried confirmation stays a duplicate.
 */
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

  /** At ingest: the recorded payment a bank SMS debit belongs to, if any. */
  public Optional<BiTransaction> recordedFor(
      UUID userId, String bankCode, BigDecimal amount, LocalDate date, Set<UUID> pairedAlready) {
    return transactions
        .findUnpairedRecorded(userId, amount, date.minusDays(WINDOW_DAYS), date.plusDays(WINDOW_DAYS))
        .stream()
        .filter(t -> !pairedAlready.contains(t.getId()))
        .filter(t -> t.getOriginBankCode() == null || bankCode == null || t.getOriginBankCode().equals(bankCode))
        .findFirst();
  }

  /** When a payment is recorded: the bank SMS already stored for it, if any. */
  public Optional<BiTransaction> smsFor(UUID userId, String bankCode, BigDecimal amount, LocalDate date) {
    return transactions
        .findUnpairedSms(userId, amount, date.minusDays(WINDOW_DAYS), date.plusDays(WINDOW_DAYS))
        .stream()
        .filter(t -> bankCode == null || bankCode.equals(bankOf(t)))
        .findFirst();
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
