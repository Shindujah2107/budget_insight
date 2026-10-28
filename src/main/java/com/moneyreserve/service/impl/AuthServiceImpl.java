package com.moneyreserve.service.impl;

import com.moneyreserve.config.AuthProperties;
import com.moneyreserve.config.Caller;
import com.moneyreserve.domain.AppRole;
import com.moneyreserve.domain.BiAppUser;
import com.moneyreserve.domain.BiAuthOtp;
import com.moneyreserve.domain.BiRefreshToken;
import com.moneyreserve.domain.OtpPurpose;
import com.moneyreserve.dto.AuthCodeSentResponse;
import com.moneyreserve.dto.AuthLoginRequest;
import com.moneyreserve.dto.AuthProfileUpdateRequest;
import com.moneyreserve.dto.AuthRegisterRequest;
import com.moneyreserve.dto.AuthTokenResponse;
import com.moneyreserve.dto.AuthUserDto;
import com.moneyreserve.dto.AuthVerifyRequest;
import com.moneyreserve.dto.DataErasureResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAppUserRepository;
import com.moneyreserve.repository.BiAuthOtpRepository;
import com.moneyreserve.repository.BiRefreshTokenRepository;
import com.moneyreserve.service.AuthService;
import com.moneyreserve.service.DataRightsService;
import com.moneyreserve.service.sms.SmsSender;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mobile number + SMS code sign-in.
 *
 * <p>The rules, all from configuration ({@link AuthProperties.Otp}): a code is 6 digits, lives 5
 * minutes, allows 5 wrong guesses, is single-use, and is replaced by any newer code to the same
 * number. A number may be sent a code at most every 30 seconds and 10 times an hour.
 *
 * <p>{@code verify} and {@code refresh} must not roll back when they refuse: a wrong guess has to
 * count, and a reused refresh token has to revoke its family, even though the call fails.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

  /** Sri Lankan mobile numbers: +94 7X XXX XXXX. */
  private static final Pattern SL_MOBILE = Pattern.compile("^\\+947\\d{8}$");

  private static final SecureRandom RANDOM = new SecureRandom();

  private final AuthProperties properties;
  private final BiAppUserRepository users;
  private final BiAuthOtpRepository otps;
  private final BiRefreshTokenRepository refreshTokens;
  private final JwtTokenService tokens;
  private final SmsSender sms;
  private final DataRightsService dataRights;

  // ---------------------------------------------------------------------------------
  // Codes
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public AuthCodeSentResponse register(AuthRegisterRequest request) {
    String phone = normalisePhone(request.phone());
    String email = request.email() == null || request.email().isBlank() ? null : request.email().trim();
    BiAppUser user = users.findByPhone(phone).orElse(null);
    if (user != null && user.isVerified()) {
      throw new BudgetInsightsException(ErrorCode.AUTH_PHONE_TAKEN);
    }
    if (user == null) {
      user = BiAppUser.builder().phone(phone).build();
    }
    // A pending sign-up is replaced, not merged: whoever finishes it with the code owns it.
    user.setFullName(request.fullName().trim());
    user.setEmail(email);
    users.save(user);
    return sendCode(phone, OtpPurpose.REGISTER);
  }

  @Override
  @Transactional
  public AuthCodeSentResponse login(AuthLoginRequest request) {
    String phone = normalisePhone(request.phone());
    BiAppUser user =
        users
            .findByPhone(phone)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    return sendCode(phone, user.isVerified() ? OtpPurpose.LOGIN : OtpPurpose.REGISTER);
  }

  private AuthCodeSentResponse sendCode(String phone, OtpPurpose purpose) {
    AuthProperties.Otp rules = properties.getOtp();
    OffsetDateTime now = OffsetDateTime.now();

    BiAuthOtp last = otps.findFirstByPhoneOrderByCreatedAtDesc(phone).orElse(null);
    if (last != null) {
      OffsetDateTime allowedAt = last.getCreatedAt().plus(rules.getResendInterval());
      if (allowedAt.isAfter(now)) {
        throw refusal(ErrorCode.AUTH_CODE_RESEND_TOO_SOON, secondsUntil(allowedAt, now));
      }
    }
    if (otps.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1)) >= rules.getMaxSendsPerHour()) {
      throw refusal(ErrorCode.AUTH_TOO_MANY_ATTEMPTS, Duration.ofHours(1).toSeconds());
    }

    // Only the newest code works.
    for (BiAuthOtp open : otps.findByPhoneAndConsumedAtIsNull(phone)) {
      open.setConsumedAt(now);
    }

    String code = newCode(rules);
    UUID id = UUID.randomUUID();
    otps.save(
        BiAuthOtp.builder()
            .id(id)
            .phone(phone)
            .purpose(purpose)
            .codeHash(codeHash(id, code))
            .attempts(0)
            .createdAt(now)
            .expiresAt(now.plus(rules.getTtl()))
            .build());

    try {
      sms.send(
          phone,
          "Your MoneyReserve code is "
              + code
              + ". It expires in "
              + rules.getTtl().toMinutes()
              + " minutes. Never share it with anyone.");
    } catch (RuntimeException ex) {
      log.error("Sign-in SMS could not be sent ({})", ex.getClass().getSimpleName(), ex);
      // Rolls the code back with the transaction, so a failed send doesn't start the resend timer.
      throw new BudgetInsightsException(ErrorCode.AUTH_SMS_FAILED);
    }
    return new AuthCodeSentResponse(
        phone, purpose, rules.getTtl().toSeconds(), rules.getResendInterval().toSeconds());
  }

  @Override
  @Transactional(noRollbackFor = BudgetInsightsException.class)
  public AuthTokenResponse verify(AuthVerifyRequest request) {
    String phone = normalisePhone(request.phone());
    OffsetDateTime now = OffsetDateTime.now();
    int maxAttempts = properties.getOtp().getMaxAttempts();

    BiAuthOtp otp = otps.findFirstByPhoneOrderByCreatedAtDesc(phone).orElse(null);
    if (otp == null || otp.getPurpose() != request.purpose() || !otp.isOpen(now)) {
      if (otp != null && otp.getAttempts() >= maxAttempts) {
        throw new BudgetInsightsException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
      }
      throw new BudgetInsightsException(ErrorCode.AUTH_CODE_EXPIRED);
    }

    if (!JwtTokenService.sameHash(otp.getCodeHash(), codeHash(otp.getId(), request.code()))) {
      otp.setAttempts(otp.getAttempts() + 1);
      if (otp.getAttempts() >= maxAttempts) {
        otp.setConsumedAt(now);
        throw new BudgetInsightsException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
      }
      throw new BudgetInsightsException(ErrorCode.AUTH_CODE_INVALID);
    }

    otp.setConsumedAt(now);
    BiAppUser user =
        users
            .findByPhone(phone)
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    if (!user.isVerified()) {
      user.setVerifiedAt(now);
    }
    applyConfiguredRole(user);
    return issue(user, UUID.randomUUID());
  }

  // ---------------------------------------------------------------------------------
  // Sessions
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(noRollbackFor = BudgetInsightsException.class)
  public AuthTokenResponse refresh(String refreshToken) {
    OffsetDateTime now = OffsetDateTime.now();
    BiRefreshToken token =
        refreshTokens
            .findByTokenHash(JwtTokenService.sha256(refreshToken))
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_SESSION_EXPIRED));
    if (token.getRevokedAt() != null) {
      // Already rotated or logged out, yet presented again: the token has been copied. End every
      // session descended from the same sign-in so whoever holds a copy is out too.
      log.warn("Refresh token reuse detected; revoking its session family");
      refreshTokens.revokeFamily(token.getFamilyId(), now);
      throw new BudgetInsightsException(ErrorCode.AUTH_SESSION_EXPIRED);
    }
    if (!token.getExpiresAt().isAfter(now)) {
      throw new BudgetInsightsException(ErrorCode.AUTH_SESSION_EXPIRED);
    }
    BiAppUser user =
        users
            .findById(token.getUserId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_SESSION_EXPIRED));
    token.setRevokedAt(now);
    applyConfiguredRole(user);
    return issue(user, token.getFamilyId());
  }

  @Override
  @Transactional
  public void logout(String refreshToken) {
    refreshTokens
        .findByTokenHash(JwtTokenService.sha256(refreshToken))
        .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), OffsetDateTime.now()));
  }

  @Override
  @Transactional(readOnly = true)
  public AuthUserDto me(Caller caller) {
    return users
        .findById(caller.userId())
        .map(AuthServiceImpl::toDto)
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
  }

  @Override
  @Transactional
  public AuthUserDto updateProfile(Caller caller, AuthProfileUpdateRequest request) {
    BiAppUser user =
        users
            .findById(caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    user.setFullName(request.fullName().trim());
    user.setEmail(
        request.email() == null || request.email().isBlank() ? null : request.email().trim());
    return toDto(users.save(user));
  }

  @Override
  @Transactional
  public DataErasureResponse deleteAccount(Caller caller) {
    BiAppUser user =
        users
            .findById(caller.userId())
            .orElseThrow(() -> new BudgetInsightsException(ErrorCode.AUTH_ACCOUNT_NOT_FOUND));
    // Insights data first, with the same erasure the data-rights route uses, so the two can never
    // disagree about what "all of a customer's data" means. One transaction: if any step fails,
    // nothing is deleted and the customer can try again.
    DataErasureResponse erased = dataRights.erase(new Caller(user.getId()));
    refreshTokens.deleteByUserId(user.getId());
    otps.deleteByPhone(user.getPhone());
    users.deleteById(user.getId());
    log.info("App account {} deleted with its Insights data ({} rows)", user.getId(), erased.getRowsRemoved());
    return erased;
  }

  // ---------------------------------------------------------------------------------

  private AuthTokenResponse issue(BiAppUser user, UUID familyId) {
    byte[] raw = new byte[32];
    RANDOM.nextBytes(raw);
    String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    OffsetDateTime now = OffsetDateTime.now();
    refreshTokens.save(
        BiRefreshToken.builder()
            .userId(user.getId())
            .familyId(familyId)
            .tokenHash(JwtTokenService.sha256(refresh))
            .expiresAt(now.plus(properties.getRefreshTokenTtl()))
            .build());
    return new AuthTokenResponse(
        "Bearer",
        tokens.issueAccessToken(user),
        properties.getAccessTokenTtl().toSeconds(),
        refresh,
        properties.getRefreshTokenTtl().toSeconds(),
        toDto(user));
  }

  /**
   * Staff numbers from configuration become SUPER_ADMIN; a number taken off the list goes back to
   * CUSTOMER. Applied at every sign-in and refresh, so a change takes effect within one access-token
   * lifetime without anyone touching the database.
   */
  private void applyConfiguredRole(BiAppUser user) {
    boolean listed =
        properties.getSuperAdminPhones().stream()
            .map(AuthServiceImpl::normaliseQuietly)
            .anyMatch(user.getPhone()::equals);
    if (listed) {
      user.setRole(AppRole.SUPER_ADMIN);
    } else if (user.getRole() == AppRole.SUPER_ADMIN) {
      user.setRole(AppRole.CUSTOMER);
    }
  }

  private static String normaliseQuietly(String phone) {
    try {
      return normalisePhone(phone);
    } catch (BudgetInsightsException ex) {
      return "";
    }
  }

  /** Accepts +94 7X…, 94 7X…, 07X… or 7X…, with or without spaces and dashes. */
  static String normalisePhone(String input) {
    String digits = input == null ? "" : input.replaceAll("[\\s\\-()]", "");
    if (digits.startsWith("+")) {
      digits = digits.substring(1);
    }
    if (digits.startsWith("94") && digits.length() == 11) {
      digits = digits.substring(2);
    } else if (digits.startsWith("0") && digits.length() == 10) {
      digits = digits.substring(1);
    }
    String e164 = "+94" + digits;
    if (!SL_MOBILE.matcher(e164).matches()) {
      throw new BudgetInsightsException(ErrorCode.AUTH_PHONE_INVALID);
    }
    return e164;
  }

  private String newCode(AuthProperties.Otp rules) {
    String fixed = rules.getFixedCode();
    if (fixed != null && !fixed.isBlank()) {
      return fixed;
    }
    StringBuilder code = new StringBuilder(rules.getLength());
    for (int i = 0; i < rules.getLength(); i++) {
      code.append(RANDOM.nextInt(10));
    }
    return code.toString();
  }

  /** Bound to the row id, so equal codes on two rows hash differently. */
  private String codeHash(UUID otpId, String code) {
    return tokens.hmac(otpId + ":" + code);
  }

  private static BudgetInsightsException refusal(ErrorCode code, long retryAfterSeconds) {
    return new BudgetInsightsException(code, code.getDefaultMessage(), retryAfterSeconds);
  }

  private static long secondsUntil(OffsetDateTime at, OffsetDateTime now) {
    return Math.max(1, Duration.between(now, at).toSeconds() + 1);
  }

  private static AuthUserDto toDto(BiAppUser user) {
    return new AuthUserDto(
        user.getId(),
        user.getFullName(),
        user.getPhone(),
        user.getEmail(),
        user.getRole(),
        user.getVerifiedAt());
  }
}
