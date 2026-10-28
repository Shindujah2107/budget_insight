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
    String provider = auth.getSms().getProvider() == null ? "log" : auth.getSms().getProvider();
    boolean realSms = "textlk".equalsIgnoreCase(provider);
    if (realSms) {
      AuthProperties.TextLk textLk = auth.getSms().getTextLk();
      if (isBlank(textLk.getApiToken()) || isBlank(textLk.getSenderId())) {
        throw new IllegalStateException(
            "moneyreserve.auth.sms.provider is textlk but TEXTLK_API_TOKEN or TEXTLK_SENDER_ID is"
                + " missing.");
      }
      if (textLk.getSenderId().length() > 11) {
        throw new IllegalStateException("TEXTLK_SENDER_ID can be at most 11 characters.");
      }
    } else if (!"log".equalsIgnoreCase(provider)) {
      throw new IllegalStateException(
          "moneyreserve.auth.sms.provider must be log or textlk, not '" + provider + "'.");
    }
    List<String> active = List.of(environment.getActiveProfiles());
    boolean devProfile = !active.isEmpty() && DEV_PERMITTED_PROFILES.containsAll(active);
    if (!devProfile && (!realSms || fixedCode)) {
      // The log sender writes codes to the log, and a fixed code would let anyone sign in.
      log.error(
          "App sign-in with the log SMS sender or a fixed code only runs on local/test; profiles {}",
          active);
      throw new IllegalStateException(
          "Outside local/test, app sign-in needs a real SMS sender (SMS_PROVIDER=textlk) and no"
              + " fixed code.");
    }
    log.info(
        "App sign-in: enabled=true, sms={}, fixedCode={}, accessTtl={}, refreshTtl={}",
        realSms ? "textlk" : "log",
        fixedCode,
        auth.getAccessTokenTtl(),
        auth.getRefreshTokenTtl());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
