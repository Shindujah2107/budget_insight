package com.moneyreserve.service.sms;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The only SMS sender for now: writes each SMS to the log instead of sending it, so a developer can
 * read the code in the backend's terminal. {@code AuthDevModeGuard} refuses to start sign-in
 * outside local and test, because a logged code would let anyone with log access sign in. Add a
 * real sender (an SMS gateway) behind {@link SmsSender} before any deployment.
 */
@Slf4j
@Component
public class LoggingSmsSender implements SmsSender {

  private static final Pattern CODE = Pattern.compile("\\b\\d{4,8}\\b");
  private static final String RULE = "=".repeat(60);

  @Override
  public void send(String phone, String text) {
    Matcher code = CODE.matcher(text);
    log.info(
        "\n{}\n  SIGN-IN CODE for {}: {}   (dev only, no SMS was sent)\n  {}\n{}",
        RULE,
        phone,
        code.find() ? code.group() : "(none)",
        text,
        RULE);
  }
}
