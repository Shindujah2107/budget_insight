package com.moneyreserve.service;

import com.moneyreserve.dto.BillDetailResponse;
import com.moneyreserve.dto.BillDto;
import com.moneyreserve.dto.BillsResponse;
import com.moneyreserve.dto.CreateBillRequest;
import com.moneyreserve.dto.UpdateBillRequest;
import com.moneyreserve.config.Caller;
import java.time.LocalDate;
import java.util.UUID;

/** The customer's recurring bills: detection, their own edits, and bill alerts. */
public interface BillService {

  /** Re-detects, then lists the customer's bills (dismissed ones left out) and monthly total. */
  BillsResponse list(Caller caller);

  BillDetailResponse detail(Caller caller, UUID billId);

  BillDto create(Caller caller, CreateBillRequest request);

  BillDto update(Caller caller, UUID billId, UpdateBillRequest request);

  /** Removes a bill added by hand; any other is marked "not a bill". */
  BillsResponse delete(Caller caller, UUID billId);

  /** A suggestion and the payments it was found from (V1.27.0). */
  com.moneyreserve.dto.BillSuggestionResponse suggestion(Caller caller, UUID billId);

  /** "Track payment": the suggestion becomes a bill with the customer's own schedule. */
  BillDto track(Caller caller, UUID billId, CreateBillRequest request);

  /** Records this cycle's payment of a bill once (V1.23.0). */
  com.moneyreserve.dto.BillPaidResponse markPaid(
      Caller caller, UUID billId, com.moneyreserve.dto.MarkBillPaidRequest request);

  /** "I didn't pay it" for a cycle left as "Payment not confirmed" (V1.26.0). */
  BillDetailResponse skipCycle(Caller caller, UUID billId, UUID cycleId);

  /**
   * A bank SMS fits a bill payment the customer confirmed (from ingest). {@code link}: make the
   * SMS that payment now. Returns what the app shows ("Matched … · Undo", or the question), or null.
   */
  com.moneyreserve.dto.BillMatchDto smsMatched(
      UUID userId, com.moneyreserve.domain.BiTransaction sms, com.moneyreserve.domain.BiTransaction recorded, boolean link);

  /** "Yes, this is my … payment": the bank SMS becomes the payment recorded for the bill. */
  BillDto matchSms(Caller caller, UUID billId, com.moneyreserve.dto.BillSmsMatchRequest request);

  /** Undo a match: the SMS is its own transaction again, the bill keeps the customer's payment. */
  BillDto unmatchSms(Caller caller, UUID billId, com.moneyreserve.dto.BillSmsMatchRequest request);

  /** A payment that settled a cycle was removed: that cycle can be answered again. */
  void paymentRemoved(UUID userId, UUID transactionId);

  /** A payment that settled a cycle was corrected: the cycle shows its amount and date. */
  void paymentEdited(UUID userId, com.moneyreserve.domain.BiTransaction payment);

  /**
   * Re-detects one customer's bills from their stored payments and raises any due, jumped or
   * missed alert. Joins the caller's transaction.
   */
  void refresh(UUID userId, LocalDate today);

  /**
   * {@link #refresh} once the current transaction commits (so it sees the new payment), in its
   * own transaction, logging rather than throwing: bills never get in the way of saving a payment.
   */
  void refreshAfterCommit(UUID userId);
}
