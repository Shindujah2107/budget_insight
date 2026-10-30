package com.moneyreserve.config;

import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with a format-discovery floor below five people outside local and test.
 *
 * <p>{@code local-min-distinct-users} lets one developer's phone produce a format candidate. In a
 * deployment it would show admins the shape of a message only one or two customers received, which
 * is exactly what the floor exists to prevent. Fail-closed like {@link AuthDevModeGuard}: an explicit
 * list of permitted profiles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormatDiscoveryLocalGuard {

  private static final Set<String> PERMITTED_PROFILES = Set.of("local", "test");

  private final InsightsProperties insights;
  private final Environment environment;

  @EventListener(ApplicationReadyEvent.class)
  public void verify() {
    Integer local = insights.getFormatDiscovery().getLocalMinDistinctUsers();
    if (local == null) {
      return;
    }
    List<String> active = List.of(environment.getActiveProfiles());
    if (active.isEmpty() || !PERMITTED_PROFILES.containsAll(active)) {
      throw new IllegalStateException(
          "moneyreserve.insights.format-discovery.local-min-distinct-users is for local testing only;"
              + " unset it outside local/test (profiles "
              + active
              + ").");
    }
    log.warn("Format discovery: candidates shown from {} customer(s) (local testing only)", local);
  }
}
