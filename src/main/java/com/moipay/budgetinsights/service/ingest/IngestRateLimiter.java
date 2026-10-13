package com.moipay.budgetinsights.service.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles a device that is ingesting far faster than a person can spend (§17).
 *
 * <p><b>The bucket is keyed on the customer, not the device.</b> {@code deviceId} arrives in the
 * request body and nothing binds it to a registered handset, so a bucket keyed on it is defeated by
 * sending a fresh identifier with each batch — which would leave the write path with no throttle at
 * all and would never raise {@code INGEST_RATE_LIMITED}. It also made the bucket map grow without
 * bound, one entry per invented identifier. Keying on the authenticated {@code userId} is a
 * deliberate narrowing of §17's "per device and per user": the enforced limit is per user, and the
 * device is still named in the log line and in the compliance event so an operator can tell which
 * handset caused it. A per-device limit only becomes worth having once a device identifier is
 * something the service issues rather than something the client asserts.
 *
 * <p><b>This limiter is per instance, and that is a real limitation rather than an oversight.</b>
 * Budget Insights runs two Fargate tasks in DEV behind a load balancer that does not pin a device
 * to one of them, so the effective ceiling across the service is the configured rate multiplied by
 * the task count. A cluster-wide limit needs shared state this service does not have — it has no
 * Redis, and putting the counter in {@code bi_device_sync_state} would mean a row update on the hot
 * path and lock contention precisely when a device is retrying hardest, which is when the limiter
 * has to work. The decision recorded here is that an approximate limit that cannot fall over is
 * worth more than an exact one that adds a failure mode to ingest.
 *
 * <p>What the limiter is actually for is worth being honest about too. It is not a defence against
 * a determined attacker, who can simply use more devices; it is a brake on a looping client and the
 * trigger for the {@code INGEST_RATE_LIMITED} compliance event, which is the signal an operator
 * needs. R7 hardening is the place to revisit it if the event volume says so.
 *
 * <p>A fixed window rather than a sliding one: the burst at a window boundary is bounded by twice
 * the rate, which for this purpose is noise, and a fixed window costs one counter per device rather
 * than a timestamp queue that has to be trimmed.
 */
public final class IngestRateLimiter {

  private final int maxMessagesPerWindow;
  private final Duration window;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  public IngestRateLimiter(int maxMessagesPerWindow, Duration window) {
    this.maxMessagesPerWindow = maxMessagesPerWindow;
    this.window = window;
  }

  /**
   * Records a batch of {@code count} messages and answers whether it is within the limit.
   *
   * <p>Counts messages rather than calls, because a device can put its whole queue in one request
   * and a limit on calls would not notice.
   *
   * @return true when the batch may proceed
   */
  public boolean tryConsume(UUID userId, int count, Instant now) {
    if (maxMessagesPerWindow <= 0) {
      return true; // Disabled by configuration.
    }
    Bucket bucket = buckets.compute(key(userId), (k, existing) -> roll(existing, now));
    return bucket.count.addAndGet(count) <= maxMessagesPerWindow;
  }

  /** Seconds until this customer's window resets, for the {@code Retry-After} the client sees. */
  public long retryAfterSeconds(UUID userId, Instant now) {
    Bucket bucket = buckets.get(key(userId));
    if (bucket == null) {
      return window.toSeconds();
    }
    long remaining = window.toSeconds() - Duration.between(bucket.startedAt, now).toSeconds();
    return Math.max(1L, remaining);
  }

  /**
   * Drops buckets whose window has long expired.
   *
   * <p>Without this the map grows with every device that has ever ingested and never shrinks. It is
   * called from the ingest path rather than on a timer so the class stays free of Spring: a small
   * amount of work on a request that was already doing database writes, against an unbounded leak
   * in a long-running container.
   */
  public void evictExpired(Instant now) {
    Duration stale = window.multipliedBy(2);
    buckets.entrySet()
        .removeIf(entry -> Duration.between(entry.getValue().startedAt, now).compareTo(stale) > 0);
  }

  /** One bucket per customer. Bounded by the number of customers ingesting in a window. */
  private String key(UUID userId) {
    return userId == null ? "-" : userId.toString();
  }

  private Bucket roll(Bucket existing, Instant now) {
    if (existing == null || Duration.between(existing.startedAt, now).compareTo(window) >= 0) {
      return new Bucket(now);
    }
    return existing;
  }

  private static final class Bucket {
    private final Instant startedAt;
    private final AtomicInteger count = new AtomicInteger();

    private Bucket(Instant startedAt) {
      this.startedAt = startedAt;
    }
  }
}
