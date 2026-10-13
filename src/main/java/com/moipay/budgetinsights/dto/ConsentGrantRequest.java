package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Records that the customer agreed to let Budget Insights read their bank messages.
 *
 * <p>Sent from a dedicated consent screen, shown <b>before</b> the operating system's SMS
 * permission is requested and separate from the general Terms and Conditions. Accepting the
 * platform terms is not consent for this and never has been.
 *
 * <p>Both the version and a hash of the policy text actually displayed are required. The version
 * alone would say which policy the customer was meant to see; the hash says which one they were
 * shown. Only the pair can answer, months later, what was agreed to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConsentGrantRequest {

  /** The policy version displayed. Must be the version the service currently requires. */
  @NotBlank(message = "The consent version is required.")
  @Size(max = 16, message = "The consent version is too long.")
  private String consentVersion;

  /** Hash of the policy text the customer actually read. */
  @NotBlank(message = "The policy hash is required.")
  @Size(max = 128, message = "The policy hash is too long.")
  private String policyHash;

  /**
   * Where consent was given.
   *
   * <p>A string rather than the enum, so an unrecognised value is a clean validation failure
   * instead of a deserialisation error the customer would see as a malformed request.
   */
  @NotBlank(message = "The consent channel is required.")
  @Pattern(
      regexp = "^(ANDROID|IOS|WEB)$",
      message = "The consent channel must be ANDROID, IOS or WEB.")
  private String channel;
}
