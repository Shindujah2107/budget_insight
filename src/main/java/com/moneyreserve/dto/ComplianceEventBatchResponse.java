package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The outcome of one batch of reported detections.
 *
 * <p><b>A batch with rejected events in it is still a successful batch</b>, for the same reason it
 * is on ingest: one malformed row must not cost the device the other thirty-nine, and a device that
 * has to resend everything will resend everything, repeatedly.
 *
 * <p>Two conditions do refuse the whole batch, and both are properties of the batch rather than of
 * an event: consent that is not in force, and a device over its rate limit. Neither writes
 * anything.
 *
 * <p><b>What this response promises.</b> Unlike the compliance events the server raises for itself,
 * which are written in their own transaction and whose failure is deliberately swallowed so that a
 * correctly rejected ingest does not become a 500, a {@code RECORDED} here means the row is
 * committed. Recording the incident <em>is</em> the request on this route, so a failed write has to
 * reach the caller as a failure — otherwise the device clears its queue and the incident is lost
 * everywhere except a log line.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComplianceEventBatchResponse {

  /** Events written to the compliance record by this call. */
  private int recorded;

  /** Events already reported under the same client reference. Not an error. */
  private int duplicates;

  private int rejected;

  /** One entry per submitted event, in the order they were sent. */
  private List<ComplianceEventResultItem> results;
}
