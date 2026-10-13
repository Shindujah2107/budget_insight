package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.InsightsProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The only form in which a user appears in SMS format discovery: an HMAC of their id.
 *
 * <p><b>Why an HMAC and not a salted hash.</b> User ids are UUIDs that other systems know. A plain
 * digest of one — salted or not, once the salt is known — can be reversed by hashing every id in
 * the user table. With a keyed MAC that needs the key as well, which lives in Secrets Manager and
 * nowhere near the database.
 *
 * <p><b>Why the key is never rotated while samples exist.</b> Withdrawal and erasure find a user's
 * samples by computing this value again. A rotated key would leave the old rows unreachable until
 * they expire, which is exactly the window a customer who withdrew must not wait out. The 90-day
 * sample lifetime is what bounds how long any hash exists.
 *
 * <p>A domain prefix keeps this value from ever equalling any other hash of the same id that a
 * future feature might compute under the same key.
 */
@Component
@RequiredArgsConstructor
public class UserHasher {

  private static final String DOMAIN = "bi:format-discovery:user:";

  private final InsightsProperties properties;

  /**
   * @return the hex HMAC-SHA256, or {@code null} when no key is configured (callers refuse the
   *     request before getting here, so null means "nothing to find" on the deletion paths)
   */
  public String hash(UUID userId) {
    if (userId == null || !properties.getFormatDiscovery().hasUserHashKey()) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(
              properties.getFormatDiscovery().getUserHashKey().getBytes(StandardCharsets.UTF_8),
              "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((DOMAIN + userId).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HmacSHA256 is not available", ex);
    }
  }
}
