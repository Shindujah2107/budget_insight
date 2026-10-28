package com.moneyreserve.service.impl;

import com.moneyreserve.repository.BiReferenceReleaseRepository;
import java.time.Duration;
import java.time.Instant;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The current reference release number for the admin screens, cached for 30 seconds.
 *
 * <p>Handsets do not use this cache: {@code /reference/sync} and ingest read the release table on
 * every call, so a publish reaches the next device call on every instance.
 */
@Component
@RequiredArgsConstructor
public class AdminReleaseVersion {

  private static final Duration TTL = Duration.ofSeconds(30);

  private final BiReferenceReleaseRepository releases;

  private volatile int cached = -1;
  private volatile Instant cachedAt = Instant.EPOCH;

  public int current() {
    Instant now = Instant.now();
    if (cached < 0 || now.isAfter(cachedAt.plus(TTL))) {
      cached = Math.max(1, releases.findCurrentVersion());
      cachedAt = now;
    }
    return cached;
  }

  public void evict() {
    cached = -1;
  }

  private double gaugeValue() {
    try {
      return current();
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }

  /** {@code bi_reference_version}: the release handsets are told to follow (doc §1.7). */
  @Autowired
  void registerGauge(ObjectProvider<MeterRegistry> meters) {
    MeterRegistry registry = meters.getIfAvailable();
    if (registry != null) {
      Gauge.builder("bi_reference_version", this, AdminReleaseVersion::gaugeValue)
          .register(registry);
    }
  }
}
