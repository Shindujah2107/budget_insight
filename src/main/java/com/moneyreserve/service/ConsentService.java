package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.ConsentGrantRequest;
import com.moneyreserve.dto.ConsentResponse;
import java.util.UUID;

/**
 * Consent to read the customer's bank messages: giving it, reading it, taking it back.
 *
 * <p>Append-only. A withdrawal writes a new record rather than editing the grant, so the history of
 * what was agreed and when survives any later dispute. The current position is the latest record.
 *
 * <p>Consent is checked on every call that reads bank messages, which is what makes withdrawal take
 * effect on the next request rather than at the next deployment. Nothing in the service reads bank
 * messages yet — ingest is R4 — so {@link #requireActiveConsent} currently has no caller. It is
 * built here rather than with ingest because the rule it enforces belongs to this release, and
 * because an enforcement point written alongside the thing it guards tends to be written to fit it.
 */
public interface ConsentService {

  /**
   * Records consent.
   *
   * <p>A re-grant of the same version and text writes nothing new, which absorbs the ordinary
   * retry: a phone resending after a dropped response, or an app granting on every launch. The
   * check is a read followed by a write with no lock, so two genuinely simultaneous grants can
   * still produce two rows. That is redundant history rather than wrong history -- the latest row
   * is correct either way -- and locking a table whose whole purpose is to be appended to would
   * cost more than it saves.
   */
  ConsentResponse grant(Caller caller, ConsentGrantRequest request);

  /** The customer's current position. Never an error, even when they have never consented. */
  ConsentResponse current(Caller caller);

  /**
   * Withdraws consent. Withdrawing when none is in force writes nothing and is not an error.
   *
   * <p>Same caveat as {@link #grant} on genuinely simultaneous calls.
   */
  ConsentResponse withdraw(Caller caller, String channel);

  /**
   * The gate every message-reading path must pass.
   *
   * <p>Refuses with {@code MR.CNS.E.001} when consent is absent or withdrawn, and with
   * {@code MR.CNS.E.002} when it was given against a policy version no longer in force.
   */
  void requireActiveConsent(UUID userId);
}
