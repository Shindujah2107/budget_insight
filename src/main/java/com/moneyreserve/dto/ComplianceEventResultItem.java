package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What happened to one reported event.
 *
 * <p>Every event that went in comes back here, in order, keyed by the {@code clientRef} the device
 * sent. That is the contract that lets a device clear its queue precisely rather than guessing from
 * a count.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComplianceEventResultItem {

  /** Echoed exactly as the device sent it. */
  private String clientRef;

  /** RECORDED, DUPLICATE or REJECTED. */
  private String status;

  /** The MR.CMP.* code on a REJECTED event. */
  private String error;
}
