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
 * Refuses to run app sign-in with development shortcuts outside local and test, and refuses to run
 * it at all without a usable signing key.
 *
 * <p>The two shortcuts are a fixed one-time code and the log SMS sender (the only sender for now),
 * which writes each code to the application log. Either one in a deployed environment would let anyone with log
 * access, or anyone who knows the fixed code, sign in as any customer. Fail-closed like {@link
 * InsightsStubModeGuard}: an explicit list of permitted profiles, so a new profile is protected
 * without anyone remembering to add it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthDevModeGuard {

  private static final Set<String> DEV_PERMITTED_PROFILES = Set.of("local", "test");
  private static final int MIN_SECRET_BYTES = 32;

  private final AuthProperties auth;
  private final Environment environment;

  @EventListener(ApplicationReadyEvent.class)
  public void verify() {
    if (!auth.isEnabled()) {
      log.info("App sign-in: enabled=false");
      return;
    }
    if (auth.getJwtSecret() == null
        || auth.getJwtSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "moneyreserve.insights.auth.enabled is true but jwt-secret (BI_JWT_SECRET) is missing or "
              + "shorter than "
              + MIN_SECRET_BYTES
              + " bytes.");
    }
    boolean fixedCode =
        auth.getOtp().getFixedCode() != null && !auth.getOtp().getFixedCode().isBlank();
    List<String> active = List.of(environment.getActiveProfiles());
    boolean devProfile = !active.isEmpty() && DEV_PERMITTED_PROFILES.containsAll(active);
    if (!devProfile) {
      // The only SMS sender writes codes to the log, and a fixed code would let anyone sign in.
      log.error(
          "App sign-in has no real SMS sender yet and only runs on local/test; profiles {}", active);
      throw new IllegalStateException(
          "App sign-in only runs on the local or test profile until a real SMS sender exists.");
    }
    log.info(
        "App sign-in: enabled=true, sms=log, fixedCode={}, accessTtl={}, refreshTtl={}",
        fixedCode,
        auth.getAccessTokenTtl(),
        auth.getRefreshTokenTtl());
  }
}
