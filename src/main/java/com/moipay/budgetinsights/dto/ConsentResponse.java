package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The customer's current consent position.
 *
 * <p>Returned by all three consent operations, so the client never has to infer the new state from
 * the fact that a call succeeded.
 *
 * <p>{@code consentGiven} and {@code upToDate} are separate on purpose. A customer can have
 * consented and still be out of date, because the policy text has changed since. That is not the
 * same as never having consented, and the screen should not treat it as such.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsentResponse {

  /** True when consent is in force right now: granted, and not since withdrawn. */
  private boolean consentGiven;

  /** The policy version the service requires today. Always present. */
  private String currentVersion;

  /** The version recorded on the latest consent record. Absent when there is none. */
  private String consentVersion;

  /** True when {@code consentVersion} matches {@code currentVersion}. Absent when never granted. */
  private Boolean upToDate;

  /** Hash of the policy text that was shown. */
  private String policyHash;

  /** ANDROID, IOS or WEB. */
  private String channel;

  private OffsetDateTime grantedAt;
  private OffsetDateTime withdrawnAt;
}
