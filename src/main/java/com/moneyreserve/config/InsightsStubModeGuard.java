package com.moneyreserve.config;

import com.moneyreserve.config.InsightsProperties;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to run with Budget Insights stub mode enabled outside a known non-production profile.
 *
 * <p>Stub mode serves invented transactions and budgets. Reaching production with it on would show
 * customers spending figures that are not theirs, which is worse than the feature being
 * unavailable.
 *
 * <p>The check is fail-closed on purpose. It allows an explicit list of non-production profiles
 * rather than blocking an explicit list of production ones, so a profile added later — a {@code
 * prod}, say — is protected without anyone having to remember to add it here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightsStubModeGuard {

  /**
   * Profiles on which fabricated spending data may be served.
   *
   * <p>Local development and tests only; any other profile, current or future, is refused, because
   * fixture data reaching real customers would be worse than the feature being unavailable.
   */
  private static final Set<String> STUB_PERMITTED_PROFILES =
      Set.of("local", "default", "test");

  private final InsightsProperties properties;
  private final Environment environment;

  @EventListener(ApplicationReadyEvent.class)
  public void verify() {
    if (!properties.getStub().isEnabled()) {
      // One line per boot saying which mode this environment is in. Without it, "the app is
      // returning 501" and "the feature is switched off" look identical from the outside.
      log.info(
          "Budget Insights: enabled={}, stub=false, lookbackMonths={}, currency={}",
          properties.isEnabled(),
          properties.getLookbackMonths(),
          properties.getCurrency());
      return;
    }

    List<String> active = List.of(environment.getActiveProfiles());
    boolean permitted = !active.isEmpty() && STUB_PERMITTED_PROFILES.containsAll(active);

    if (!permitted) {
      // Logged before the throw. An exception out of ApplicationReadyEvent can be reported by the
      // framework in a form that buries the cause, and a refused boot has to say why in one line.
      log.error(
          "REFUSING TO START: Budget Insights stub mode is enabled on profile(s) {}, which are not "
              + "in the permitted non-production set {}.",
          active,
          STUB_PERMITTED_PROFILES);
      throw new IllegalStateException(
          "moneyreserve.insights.stub.enabled is true but the active profile(s) "
              + active
              + " are not in the permitted non-production set "
              + STUB_PERMITTED_PROFILES
              + ". Stub mode serves fabricated spending data and must never be enabled outside "
              + "development or staging.");
    }

    log.info(
        "Budget Insights STUB MODE is enabled (profiles={}). Responses are fixture data, "
            + "not real customer transactions.",
        active);
  }
}
