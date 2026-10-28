package com.moneyreserve.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * App sign-in: mobile number + SMS one-time code, after which this service issues its own JWTs.
 *
 * <p>Ships off, like every other flag here. With {@code enabled=false} the {@code /api/auth} routes
 * answer 404 and no bearer token is accepted, so a deployment that has not been given a signing key
 * behaves exactly as it did before this existed.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "moneyreserve.auth")
public class AuthProperties {

  /** Master switch for the app sign-in routes and bearer-token authentication. */
  private boolean enabled = false;

  /**
   * HMAC key for signing access tokens and hashing one-time codes (HS256, at least 32 bytes).
   *
   * <p>No default. Comes from {@code BI_JWT_SECRET}; only the local profile supplies one. Rotating it signs
   * every customer out, which is the intended way to revoke all access tokens at once.
   */
  private String jwtSecret = "";

  /** Written into the {@code iss} claim and required on every token presented. */
  private String issuer = "moneyreserve-api";

  /** Access-token lifetime. Short, because an access token cannot be revoked before it expires. */
  private Duration accessTokenTtl = Duration.ofMinutes(15);

  /** Refresh-token lifetime. Each use replaces the token, so this is the idle limit, not a cap. */
  private Duration refreshTokenTtl = Duration.ofDays(30);


  /**
   * Mobile numbers that are given the SUPER_ADMIN role (the admin API) when they sign in. Any
   * format the sign-in screens accept; compared in E.164. Removing a number demotes it to
   * CUSTOMER on that user's next sign-in or token refresh.
   */
  private List<String> superAdminPhones = new ArrayList<>();

  private Otp otp = new Otp();

  @Getter
  @Setter
  public static class Otp {
    private int length = 6;
    private Duration ttl = Duration.ofMinutes(5);
    /** Wrong guesses allowed on one code before it is burnt and a new one must be requested. */
    private int maxAttempts = 5;
    /** Minimum gap between two codes sent to the same number. */
    private Duration resendInterval = Duration.ofSeconds(30);
    /** Codes one number may be sent per hour, whatever the purpose. Caps SMS cost and abuse. */
    private int maxSendsPerHour = 10;
    /**
     * A fixed code used instead of a random one. Local development only:
     * {@link AuthDevModeGuard} refuses to start with this set on any other profile.
     */
    private String fixedCode = "";
  }
}
