package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.ReferenceSyncResponse;

/**
 * Serves the sender allowlist and the parser templates to a device (BI-13).
 *
 * <p>This is the only reason the device can read bank messages without MoneyReserve seeing them: the
 * rules travel to the handset, the message body never leaves it.
 */
public interface ReferenceSyncService {

  /**
   * The current reference set, and a note of where this device stands against it. Today's
   * contract: senders and templates only.
   *
   * @param templateVersion the version the device currently holds, or null if it holds none
   */
  default ReferenceSyncResponse sync(Caller caller, String deviceId, Integer templateVersion) {
    return sync(caller, deviceId, templateVersion, null);
  }

  /**
   * As above, plus the SMS format-discovery vocabulary when {@code vocabularyVersion} is given and
   * older than the server's (and Phase 2 is on). A phone that does not send it gets exactly the
   * response it gets today.
   */
  ReferenceSyncResponse sync(
      Caller caller, String deviceId, Integer templateVersion, Integer vocabularyVersion);
}
