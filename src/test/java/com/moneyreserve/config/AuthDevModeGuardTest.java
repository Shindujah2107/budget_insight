package com.moneyreserve.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Which sign-in setups may start where. */
class AuthDevModeGuardTest {

  private static AuthProperties auth(String provider, String token, String sender, String fixed) {
    AuthProperties auth = new AuthProperties();
    auth.setEnabled(true);
    auth.setJwtSecret("a-test-signing-key-that-is-long-enough-32");
    auth.getSms().setProvider(provider);
    auth.getSms().getTextLk().setApiToken(token);
    auth.getSms().getTextLk().setSenderId(sender);
    auth.getOtp().setFixedCode(fixed);
    return auth;
  }

  private static void verify(AuthProperties auth, String... profiles) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    new AuthDevModeGuard(auth, env).verify();
  }

  @Test
  @DisplayName("the log sender runs locally but not in a deployment")
  void logSenderLocalOnly() {
    assertDoesNotThrow(() -> verify(auth("log", "", "", "123456"), "local"));
    assertThrows(IllegalStateException.class, () -> verify(auth("log", "", "", ""), "prod"));
    assertThrows(IllegalStateException.class, () -> verify(auth("log", "", "", "")));
  }

  @Test
  @DisplayName("Text.lk runs anywhere once its token and sender ID are set")
  void textLk() {
    assertDoesNotThrow(() -> verify(auth("textlk", "token", "MoneyRsrv", ""), "prod"));
    assertThrows(IllegalStateException.class, () -> verify(auth("textlk", "", "MoneyRsrv", ""), "prod"));
    assertThrows(IllegalStateException.class, () -> verify(auth("textlk", "token", "", ""), "prod"));
    assertThrows(
        IllegalStateException.class,
        () -> verify(auth("textlk", "token", "SenderIdTooLong", ""), "prod"));
  }

  @Test
  @DisplayName("a fixed code never runs in a deployment, even with real SMS")
  void fixedCodeLocalOnly() {
    assertThrows(
        IllegalStateException.class, () -> verify(auth("textlk", "token", "MoneyRsrv", "123456"), "prod"));
  }

  @Test
  @DisplayName("an unknown provider is refused")
  void unknownProvider() {
    assertThrows(IllegalStateException.class, () -> verify(auth("carrier-pigeon", "", "", ""), "local"));
  }
}
