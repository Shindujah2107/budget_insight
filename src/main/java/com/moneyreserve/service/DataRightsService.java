package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.DataErasureResponse;
import com.moneyreserve.dto.DataExportResponse;
import java.util.UUID;

/**
 * Export and erasure — BI-27 and BI-28.
 *
 * <p>The two are one service on purpose. Both answer the same question, from opposite ends: what
 * is all of this customer's data? An export that misses a table understates what is held; an
 * erasure that misses the same table leaves it behind. Implemented separately they would drift the
 * first time a table was added, and the drift would be silent in both directions. Here they are
 * driven from a single ordered list, so a new table is either in both or in neither.
 */
public interface DataRightsService {

  /**
   * Builds the customer's export and returns the receipt for it.
   *
   * <p>Built synchronously, then handed over by id. The published contract is request-then-fetch,
   * which reads as asynchronous and leaves room to become so; six months of one customer's
   * transactions is small enough that queueing it would add a state machine and a failure mode for
   * no gain today.
   */
  DataExportResponse requestExport(Caller caller);

  /**
   * The export document itself, as JSON.
   *
   * @throws com.moneyreserve.exception.BudgetInsightsException if the id is not this
   *     caller's, or the export has expired
   */
  String downloadExport(Caller caller, UUID exportId);

  /**
   * Removes every row this service holds for the customer.
   *
   * <p>Idempotent: erasing twice is not an error, and the second call reports zeros.
   */
  DataErasureResponse erase(Caller caller);
}
