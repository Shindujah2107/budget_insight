package com.moipay.budgetinsights.dto;

import jakarta.validation.Valid;
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
 * A device's outbound queue, in one round trip (BI-08).
 *
 * <p>Batched because a phone that has been offline comes back with a queue, not a message, and
 * because the per-message result set is what lets it clear exactly what was accepted and keep the
 * rest.
 *
 * <p>There is no user identifier here either. The owner is the authenticated caller.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestBatchRequest {

  /**
   * The device sending the batch.
   *
   * <p>Carried so a compliance event can be attributed and a misbehaving handset throttled. It is
   * hashed with a salt before it reaches {@code bi_compliance_event}; the raw value is never stored
   * beside a transaction.
   */
  @NotBlank(message = "The batch needs a device identifier.")
  @Size(max = 128, message = "The device identifier is too long.")
  private String deviceId;

  /**
   * The parser template version the device parsed with.
   *
   * <p>A device that is behind is told to sync before it ingests again. Its messages are still
   * processed — refusing spend a customer has already made because their app has not fetched a
   * regex would be the wrong trade — but the staleness is reported so the device fixes itself.
   */
  private Integer templateVersion;

  /**
   * The platform whose on-device pipeline parsed these messages: {@code ANDROID} or {@code IOS}.
   *
   * <p>Optional, defaulting to {@code ANDROID}, so every client built against the R4 contract keeps
   * working unchanged. It exists because the source is recorded on every transaction and R4 had no
   * way to say anything but Android — which would have made R6's demo, an iOS handset forwarding a
   * bank message end to end, record iOS spend as Android spend.
   *
   * <p>It describes the parser, not the device's operating system, and it is not a security
   * boundary: a client can assert either. Nothing is authorised on the strength of it.
   */
  @Pattern(regexp = "^(ANDROID|IOS)$", message = "The platform must be ANDROID or IOS.")
  private String platform;

  @NotEmpty(message = "The batch needs at least one message.")
  @Valid
  private List<IngestMessage> messages;
}
