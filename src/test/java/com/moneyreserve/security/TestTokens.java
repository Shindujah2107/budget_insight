package com.moneyreserve.security;

import com.moneyreserve.domain.AppRole;
import com.moneyreserve.domain.BiAppUser;
import com.moneyreserve.repository.BiAppUserRepository;
import com.moneyreserve.service.impl.JwtTokenService;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Signs integration-test requests in the way the app does: with a real access token for a real
 * user row.
 *
 * <p>{@link #bearer(UUID, String)} creates the user on first use (the token filter refuses tokens
 * whose user does not exist) and gives it the role asked for. Static so the many tests that build
 * headers in static helpers can use it; every test context shares one in-memory database and one
 * signing key, so whichever context registered last can mint for all of them.
 */
@Component
public class TestTokens {

  private static volatile TestTokens instance;
  private static final AtomicLong NEXT_PHONE = new AtomicLong(70_000_000L);

  private final JwtTokenService tokens;
  private final BiAppUserRepository users;

  public TestTokens(JwtTokenService tokens, BiAppUserRepository users) {
    this.tokens = tokens;
    this.users = users;
  }

  @PostConstruct
  void register() {
    instance = this;
  }

  /** A customer's access token. */
  public static String bearer(UUID userId) {
    return bearer(userId, null);
  }

  /**
   * An access token for {@code userId} with the role named in {@code roles}: anything containing
   * SUPER_ADMIN is a super admin, a bare ADMIN is an admin, everything else is a
   * customer.
   */
  public static String bearer(UUID userId, String roles) {
    TestTokens self = instance;
    if (self == null) {
      throw new IllegalStateException("No Spring test context has started yet");
    }
    AppRole role = roleOf(roles);
    BiAppUser user =
        self.users
            .findById(userId)
            .orElseGet(
                () ->
                    BiAppUser.builder()
                        .id(userId)
                        .phone("+947" + NEXT_PHONE.getAndIncrement())
                        .fullName("Test User")
                        .verifiedAt(OffsetDateTime.now())
                        .build());
    if (user.getRole() != role) {
      user.setRole(role);
    }
    user = self.users.save(user);
    return self.tokens.issueAccessToken(user);
  }

  static AppRole roleOf(String roles) {
    if (roles == null || roles.isBlank()) {
      return AppRole.CUSTOMER;
    }
    var names = Arrays.stream(roles.split(",")).map(String::trim).toList();
    if (names.contains("SUPER_ADMIN")) {
      return AppRole.SUPER_ADMIN;
    }
    return names.contains("ADMIN") ? AppRole.ADMIN : AppRole.CUSTOMER;
  }
}
