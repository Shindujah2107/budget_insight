package com.moneyreserve.service.impl;

import com.moneyreserve.config.AuthProperties;
import com.moneyreserve.config.Caller;
import com.moneyreserve.domain.BiAppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * Issues and checks the app's access tokens, and hashes the secrets that sign-in stores.
 *
 * <p>Access tokens are HS256 JWTs: {@code sub} is the user id, {@code typ}
 * is {@code access}. HS256 rather than an asymmetric key because this service is the only one that
 * signs or reads them; if another service ever needs to verify them, switch to RS256 and publish
 * the public key rather than sharing this secret.
 */
@Slf4j
@Component
public class JwtTokenService {

  static final String CLAIM_TYPE = "typ";
  static final String TYPE_ACCESS = "access";
  static final String CLAIM_ROLE = "role";

  private final AuthProperties properties;
  private final SecretKey key;
  private final JwtEncoder encoder;
  private final JwtDecoder decoder;

  public JwtTokenService(AuthProperties properties) {
    this.properties = properties;
    byte[] secret =
        properties.getJwtSecret() == null
            ? new byte[0]
            : properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
    if (secret.length >= 32) {
      this.key = new SecretKeySpec(secret, "HmacSHA256");
      this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
      NimbusJwtDecoder nimbus =
          NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
      nimbus.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getIssuer()));
      this.decoder = nimbus;
    } else {
      // AuthDevModeGuard stops the service when sign-in is enabled without a key; with sign-in
      // off nothing here is ever called, and no token can be accepted.
      this.key = null;
      this.encoder = null;
      this.decoder = null;
    }
  }

  public boolean isReady() {
    return key != null;
  }

  /** A signed access token for this user, valid for {@code accessTokenTtl}. */
  public String issueAccessToken(BiAppUser user) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.getIssuer())
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.getAccessTokenTtl()))
            .id(UUID.randomUUID().toString())
            .claim(CLAIM_TYPE, TYPE_ACCESS)
            // For the app's information only; the server reads the role from the user row.
            .claim(CLAIM_ROLE, user.getRole().name())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * The caller an access token names, or empty when the token is malformed, unsigned by us,
   * expired, from another issuer, or not an access token.
   */
  public Optional<Caller> readAccessToken(String token) {
    if (decoder == null) {
      return Optional.empty();
    }
    try {
      Jwt jwt = decoder.decode(token);
      if (!TYPE_ACCESS.equals(jwt.getClaimAsString(CLAIM_TYPE))) {
        return Optional.empty();
      }
      UUID user = UUID.fromString(jwt.getSubject());
      return Optional.of(new Caller(user));
    } catch (JwtException | IllegalArgumentException ex) {
      log.debug("Bearer token rejected: {}", ex.getMessage());
      return Optional.empty();
    }
  }

  /** HMAC-SHA256 of {@code value} under the signing secret, hex. Used for one-time codes. */
  public String hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("HMAC unavailable", ex);
    }
  }

  /** SHA-256, hex. Refresh tokens are 256 random bits, so a plain hash is enough to store them. */
  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  /** Constant-time comparison of two hex digests. */
  public static boolean sameHash(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }
}
