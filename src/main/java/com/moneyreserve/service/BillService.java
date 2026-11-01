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
