package com.moipay.budgetinsights.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One OTP or PIN message the device detected and destroyed.
 *
 * <p>Compliance requires a detected OTP or PIN capture to be logged as a formal incident even
 * though the message is discarded immediately. Until this contract existed that requirement was met
 * only for messages the <em>server</em> caught, which are the ones a well-behaved device never
 * sends — so the record systematically under-counted the control it exists to evidence.
 *
 * <p><b>There is no field here that can carry the message.</b> Not the body, not a fragment, not
 * the code, not the sender, not an amount. That is the control, and it is asserted by
 * {@code InsightsDtoArchitectureTest}: the device reports <em>that</em> it discarded something and
 * <em>when</em>, and nothing else. Everything the record needs beyond that — who the caller is,
 * that the source was the on-device parser, that the outcome was a discard — is decided by the
 * server, never asserted by the client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientComplianceEvent {

  /**
   * {@code OTP_DETECTED} or {@code PIN_DETECTED}, and nothing else.
   *
   * <p>A string rather than the enum so that an unreportable value is refused as
   * {@code MOI.BI.CMP.E.003} against <em>that event</em>, leaving the rest of the batch to land.
   * Binding the enum directly would make one bad row fail the whole request, which is the mistake
   * the ingest contract already avoids.
   *
   * <p>The other seven {@link com.moipay.budgetinsights.domain.ComplianceEventType} values are
   * raised by the server from things the server observed. A client must not be able to assert that
   * consent changed, that an erasure completed, or that it was throttled.
   */
  @NotBlank(message = "Each report needs an event type.")
  @Size(max = 32, message = "The event type is too long.")
  private String eventType;

  /**
   * When the device detected it, from the device's clock.
   *
   * <p>Recorded as {@code occurred_at} rather than the moment the report reached us, because a
   * handset that was offline for a day would otherwise file yesterday's incidents under today and
   * make the timeline Compliance reads untrue. A clock far enough out to be implausible is refused
   * rather than silently corrected — see {@code clock-skew-minutes}.
   */
  @NotNull(message = "Each report needs the time it was detected.")
  private OffsetDateTime detectedAt;

  /**
   * An opaque, client-generated handle for this one report, so a retry is not a second incident.
   *
   * <p>Constrained to an identifier shape — no spaces, no punctuation beyond {@code -} and
   * {@code _} — and then <b>hashed before it is stored</b>. Both together are what stop this field
   * becoming the free-text column the rest of the contract is carefully designed not to have: a
   * device cannot smuggle a code into the one table that is never purged, because what lands there
   * is a digest and not the value.
   *
   * <p>A UUID is the obvious thing to send. Whatever is sent, the server never interprets it and
   * never returns it to anyone but the caller that sent it.
   */
  @NotBlank(message = "Each report needs a client reference.")
  @Pattern(
      regexp = "^[A-Za-z0-9_-]{8,64}$",
      message = "The client reference must be 8 to 64 characters of letters, digits, _ or -.")
  private String clientRef;
}
