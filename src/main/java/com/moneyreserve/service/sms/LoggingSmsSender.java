package com.moneyreserve.service.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only SMS sender for now: writes each SMS to the log instead of sending it, so a developer can
 * read the code. {@code AuthDevModeGuard} refuses to start sign-in outside local and test, because
 * a logged code would let anyone with log access sign in. Add a real sender (an SMS gateway)
 * behind {@link SmsSender} before any deployment.
 */
@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {

  @Override
  public void send(String phone, String text) {
    log.info("[dev-sms] to {}: {}", phone, text);
  }
}
