package com.moipay.budgetinsights.service.ingest;

/**
 * Namespaces the device's idempotency key so an ingested message and a manual entry can never
 * collide (part of BI-10).
 *
 * <p>{@code bi_transaction} has one uniqueness constraint, {@code ux_bi_txn_user_idem} on
 * {@code (user_id, idempotency_key)}, and two very different producers write through it. The manual
 * path already namespaces with {@code MANUAL:}; ingest namespaces with {@code SMS:} for the same
 * reason. Without it a customer whose client generated the reference {@code abc123} by hand would
 * silently suppress a bank message that happened to hash to the same string — a transaction that
 * never appears, reported as a duplicate, with nothing in the logs to explain it.
 *
 * <p>The namespace is a storage detail. The per-message result set echoes the key <em>the device
 * sent</em>, because that is the key the device holds in its outbound queue and must match against
 * to clear it.
 */
public final class IdempotencyResolver {

  private IdempotencyResolver() {}

  /** Prefix for keys computed on a device from a bank message. */
  public static final String SMS_NAMESPACE = "SMS:";

  /**
   * The stored key for a device-computed one.
   *
   * <p>Validation happens in {@link PayloadNormaliser#requireIdempotencyKey(String)} before this is
   * called, so by here the key is a single opaque token of a known length. The namespace plus a
   * 120-character key still fits {@code varchar(128)}.
   */
  public static String forIngestedMessage(String deviceKey) {
    return SMS_NAMESPACE + deviceKey;
  }
}
