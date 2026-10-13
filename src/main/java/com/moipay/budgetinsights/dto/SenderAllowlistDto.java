package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One allowlisted SMS sender, as the device needs to see it.
 *
 * <p>The allowlist is the device's first gate: a message from a sender that is not on this list is
 * dropped before the parser runs, and a {@code SENDER_REJECTED} event is raised locally. Serving it
 * from here rather than shipping it in the app means a new bank is a data change, not a release.
 *
 * <p>No internal identifier and no timestamps. The device matches on sender and country and has no
 * use for a row id, and every field a reference payload does not carry is a field that cannot drift
 * between server and handset.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SenderAllowlistDto {

  private String bankCode;

  /** The sender ID exactly as it appears on the handset, for example a short code or an alpha tag. */
  private String senderId;

  /** ISO-3166 alpha-2. Present because the same alpha tag can belong to different banks by market. */
  private String countryCode;
}
