package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.dto.IngestBatchRequest;
import com.moipay.budgetinsights.dto.IngestBatchResponse;

/**
 * Turns a device's queue of parsed bank messages into transactions (BI-08, BI-10, BI-11).
 *
 * <p>The order of operations is fixed by §16 of the implementation plan and is not an
 * implementation detail: consent first, the OTP re-check second, and only then anything that
 * touches the database. A guard that runs after a write is not a guard.
 */
public interface IngestService {

  /**
   * Ingests one batch.
   *
   * <p>Retries once on a uniqueness collision. Two batches arriving together from the same device —
   * a retry that crossed with the original — are the ordinary case, and the second attempt finds
   * the rows the first wrote and reports them as duplicates, which is exactly what the device needs
   * to hear.
   */
  IngestBatchResponse ingest(Caller caller, IngestBatchRequest request);

  /**
   * One attempt. On the interface rather than the class because the retry above goes back through
   * the Spring proxy to get a fresh transaction, and a JDK proxy can only reach interface methods.
   * Not intended for direct use.
   */
  IngestBatchResponse ingestOnce(Caller caller, IngestBatchRequest request);
}
