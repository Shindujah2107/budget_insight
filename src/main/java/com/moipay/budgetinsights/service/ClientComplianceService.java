package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.dto.ComplianceEventBatchRequest;
import com.moipay.budgetinsights.dto.ComplianceEventBatchResponse;

/**
 * Records OTP and PIN detections that happened on the handset.
 *
 * <p>Distinct from {@link InsightsComplianceService}, which records what the <em>server</em>
 * observed, and the difference is not organisational. {@code InsightsComplianceService} writes in
 * its own transaction and swallows its failures on purpose, because there the event is a side
 * effect of a request that is usually failing anyway and a failed audit write must never turn a
 * correctly rejected payload into a 500.
 *
 * <p>Here, recording the event <b>is</b> the request. The same swallow would mean answering
 * {@code RECORDED} for a row that was never written, after which the device deletes its queue and
 * the incident exists nowhere. So this path writes inside the caller's transaction and lets a
 * failure become a failure, which is the only answer that leaves the device able to retry.
 */
public interface ClientComplianceService {

  /**
   * Records a batch of device-side detections.
   *
   * <p>Refuses the whole batch when consent is not in force or the device is over its rate limit.
   * Everything else is decided per event and reported in the response.
   */
  ComplianceEventBatchResponse record(Caller caller, ComplianceEventBatchRequest request);
}
