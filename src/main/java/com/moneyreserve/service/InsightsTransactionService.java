package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.CreateTransactionRequest;
import com.moneyreserve.dto.TransactionMutationResponse;
import com.moneyreserve.dto.UpdateTransactionRequest;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Adding, correcting and removing transactions by hand.
 *
 * <p>Three rules hold across everything here.
 *
 * <ol>
 *   <li><b>The customer's category wins.</b> Once {@code isUserCategorised} is set, no automated
 *       pass may change that category again — not on re-ingest, not when the merchant map improves.
 *       {@code autoCategoryId} keeps what the engine thought, so the gap between the two stays
 *       measurable.
 *   <li><b>Nothing is destroyed.</b> Deletes are soft and every change writes an audit row, so a
 *       customer asking why a figure moved can be answered from the data rather than from memory.
 *   <li><b>The cache is invalidated in the same transaction as the write.</b> Not after it, not on
 *       a schedule. If the write commits and the invalidation does not, the screen shows a stale
 *       total and no subsequent event will correct it.
 * </ol>
 */
public interface InsightsTransactionService {

  TransactionMutationResponse create(
      Caller caller, CreateTransactionRequest request, LocalDate from, LocalDate to);

  TransactionMutationResponse update(
      Caller caller, UUID id, UpdateTransactionRequest request, LocalDate from, LocalDate to);

  TransactionMutationResponse delete(Caller caller, UUID id, LocalDate from, LocalDate to);
}
