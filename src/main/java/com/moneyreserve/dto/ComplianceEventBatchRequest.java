package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A device's queue of discarded OTP and PIN detections, in one round trip.
 *
 * <p>Batched for the same reason ingest is: a handset that has been offline comes back with a
 * queue, not an event, and the per-event result set is what lets it clear exactly what landed.
 *
 * <p>There is no user identifier here. The owner is the authenticated caller.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceEventBatchRequest {

  /**
   * The device reporting.
   *
   * <p>Required, not optional. An incident nobody can attribute to a handset is an incident nobody
   * can act on: the whole operational value of this record is being able to see the same device
   * tripping the filter again and again. It is hashed with a salt before it reaches
   * {@code bi_compliance_event}; the raw value is never stored.
   */
  @NotBlank(message = "The report needs a device identifier.")
  @Size(max = 128, message = "The device identifier is too long.")
  private String deviceId;

  /**
   * The platform whose on-device pipeline made the detection: {@code ANDROID} or {@code IOS}.
   *
   * <p>Optional, and carried for parity with the ingest envelope. It is not a security boundary and
   * nothing is authorised on the strength of it.
   */
  @Pattern(regexp = "^(ANDROID|IOS)$", message = "The platform must be ANDROID or IOS.")
  private String platform;

  /**
   * The detections, validated one at a time rather than all at once.
   *
   * <p>Deliberately <b>not</b> {@code @Valid}. Cascading would turn one malformed row into a 400
   * for the whole request, naming no event and carrying no result set — and a device that cannot
   * tell which row was bad either resends the same queue for ever or discards all of it. On this
   * route discarding is unrecoverable: the messages these reports describe were destroyed on the
   * handset. {@code ClientComplianceServiceImpl} enforces the same constraints per event and
   * rejects a bad row on its own with {@code MR.CMP.E.005}.
   */
  @NotEmpty(message = "The report needs at least one event.")
  private List<ClientComplianceEvent> events;
}
