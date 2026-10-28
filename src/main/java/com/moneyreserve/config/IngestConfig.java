package com.moneyreserve.config;

import com.moneyreserve.service.ingest.IngestRateLimiter;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ingest rate limiter.
 *
 * <p>{@link IngestRateLimiter} is a plain class with no Spring annotations on purpose — it holds
 * the only mutable state in the ingest path and a test that exercises a throttle should not need a
 * context to do it. This is the one place that knows it is a singleton bean.
 */
@Configuration
public class IngestConfig {

  @Bean
  public IngestRateLimiter ingestRateLimiter(InsightsProperties properties) {
    return new IngestRateLimiter(
        properties.getIngest().getRateLimitMessages(),
        Duration.ofSeconds(Math.max(1, properties.getIngest().getRateLimitWindowSeconds())));
  }
}
