package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The one-way hash that lets a compliance record be correlated without identifying anybody.
 *
 * <p>Extracted from {@link InsightsComplianceServiceImpl} when a second writer to
 * {@code bi_compliance_event} appeared. Two copies of a hash are worse than they look: if the salt
 * or the algorithm ever diverged between them, the same handset would appear under two identities
 * and the correlation the record exists for would break silently, with nothing failing.
 *
 * <p>The behaviour is unchanged from the original, deliberately, including the part that looks like
 * an omission: <b>with no salt configured this returns null rather than an unsalted hash</b>. An
 * unsalted hash of a device identifier is reversible by anyone who can enumerate device
 * identifiers, so a missing salt means no hash rather than a weak one. The incident is still
 * recorded; it just cannot be grouped with the others from that handset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceIdHasher {

  private final InsightsProperties properties;

  /** Ensures the missing-salt misconfiguration is reported once per boot, not per event. */
  private final AtomicBoolean saltWarningIssued = new AtomicBoolean(false);

  /**
   * Salted SHA-256 of a device identifier, or null when there is nothing to hash or no salt to hash
   * it with.
   */
  public String hashDeviceId(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      return null;
    }
    if (!properties.hasDeviceHashSalt()) {
      // Once per boot, not once per event. A misconfiguration that logs on every compliance event
      // buries the events themselves, which are the records that actually matter here.
      if (saltWarningIssued.compareAndSet(false, true)) {
        log.error(
            "moneyreserve.insights.device-hash-salt is not set; compliance events will be "
                + "recorded "
                + "without a device hash and cannot be correlated by device.");
      }
      return null;
    }
    return sha256Hex(properties.getDeviceHashSalt() + deviceId);
  }

  /**
   * Domain separator for the client-reference digest.
   *
   * <p>So that the same salt and the same string cannot produce the same digest in two different
   * roles. It is not a secret and is not doing the work of one — the salt is.
   */
  private static final String CLIENT_REF_DOMAIN = "bi36:client-ref:";

  /**
   * A stable digest of a client-supplied reference, for duplicate suppression.
   *
   * <p>It differs from {@link #hashDeviceId} in what it is for — <em>determinism</em>, because two
   * reports of the same reference must collide or the retry is recorded twice — but not in what it
   * requires. <b>It will not hash without a salt.</b> An earlier version fell back to an unsalted
   * digest on the reasoning that duplicate suppression needs determinism rather than secrecy, and
   * that was wrong in a way worth spelling out: the value being hashed is a client-controlled
   * string of eight to sixty-four characters, so an unsalted SHA-256 of it is reversible by brute
   * force for anything low-entropy. That would have falsified the claim this whole contract rests
   * on — that a handset cannot put a code into the one table that is never purged.
   *
   * <p>A blank salt is therefore a misconfiguration rather than a degraded mode. The route refuses
   * the report with 501 before reaching here, and {@code ComplianceReportingGuard} logs the gap at
   * ERROR on every boot; this throw is the backstop behind both.
   *
   * <p><b>The digest is bound to the reporting device</b>, not to the reference alone.
   * {@code clientRef} is constrained to an identifier shape and nothing more — a handset is free to send a
   * local counter, and two customers would then collide. A collision is answered
   * {@code DUPLICATE} and never written, and the device clears its queue on the strength of that
   * answer, so the second customer's incident would be gone: exactly the under-counting this
   * feature exists to end. Mixing the device hash in makes the key unique per handset by
   * construction rather than by the client choosing good references, and per handset is the right
   * scope anyway, since a retry comes from the device that sent the original.
   *
   * <p>The corollary is that <b>rotating the salt ends duplicate suppression</b>: digests written
   * before the rotation no longer match the ones computed after it, so a report retried across a
   * rotation is recorded twice. That is a bounded, one-off cost on a rare operation, and the
   * alternative — keying a permanent record on something that is not secret — is not.
   *
   * @param deviceIdHash the reporting device's hash, from {@link #hashDeviceId}; may be null only
   *     if the caller could not produce one, in which case the digest is global
   * @throws IllegalStateException if no salt is configured
   */
  public String hashClientRef(String deviceIdHash, String clientRef) {
    if (clientRef == null || clientRef.isBlank()) {
      return null;
    }
    if (!properties.hasDeviceHashSalt()) {
      throw new IllegalStateException(
          "moneyreserve.insights.device-hash-salt is not set. Device-reported compliance events "
              + "cannot be recorded without it: an unsalted digest of a client-supplied reference "
              + "is reversible, and this record must not be able to hold a code.");
    }
    return sha256Hex(
        CLIENT_REF_DOMAIN
            + properties.getDeviceHashSalt()
            + (deviceIdHash == null ? "" : deviceIdHash)
            + ":"
            + clientRef);
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      log.error("SHA-256 unavailable; compliance event recorded without a hash", ex);
      return null;
    }
  }
}
