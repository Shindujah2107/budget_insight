package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The outcome of one ingest batch.
 *
 * <p><b>A batch with rejected messages is still a successful batch.</b> One message the device's
 * parser mangled must not cost the customer the other forty that were fine, and a device that has
 * to resend everything because of one bad row will resend everything, repeatedly. The counts and
 * the per-message results carry the failure; the HTTP status carries only whether the request
 * itself was accepted.
 *
 * <p>Two conditions do fail the whole batch, and both are about the batch rather than a message:
 * consent that is not in force, and a device over its rate limit. Neither writes anything.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestBatchResponse {

  private int accepted;

  /** Messages already ingested. Not an error, and not a reason to retry. */
  private int duplicates;

  private int rejected;

  /** One entry per submitted message, in the order they were sent. */
  private List<IngestResultItem> results;

  /**
   * Present only when the device's template version is behind the server's.
   *
   * <p>The signal to call reference sync. Absent means up to date, so a client that ignores this
   * field entirely still works.
   */
  private Integer currentTemplateVersion;
}
