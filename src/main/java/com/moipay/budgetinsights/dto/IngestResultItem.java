package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What happened to one message in the batch.
 *
 * <p>Every message that went in comes back here, in order, keyed by the idempotency key the device
 * sent. That is the contract that lets a device clear its queue precisely rather than guessing from
 * a count.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IngestResultItem {

  /** Echoed exactly as the device sent it, without the storage namespace. */
  private String idempotencyKey;

  /** ACCEPTED, DUPLICATE or REJECTED. */
  private String status;

  /** Present on ACCEPTED, and on DUPLICATE where the earlier row still exists. */
  private UUID transactionId;

  /**
   * The MOI.BI.* code on a REJECTED message.
   *
   * <p>Deliberately never {@code MOI.BI.ING.E.001} with an explanation attached: a device that sent
   * something the OTP guard caught is told the message could not be processed and nothing more.
   */
  private String error;
}
