package com.moneyreserve.config;

import com.moneyreserve.domain.BiAppUser;
import com.moneyreserve.repository.BiAppUserRepository;
import com.moneyreserve.service.impl.JwtTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the standalone app's customers from {@code Authorization: Bearer <access token>}.
 *
 * <p>The principal is a {@link Caller}, so every endpoint scopes its queries to the token's user.
 * Authorities are {@code ROLE_USER} plus {@code ROLE_<role>} from the user's row; only a
 * SUPER_ADMIN reaches the admin routes.
 *
 * <p>A missing, malformed or expired token, or one for an account that has since been deleted, leaves the request unauthenticated and the entry point
 * answers 401; this filter never answers itself. It is wired only into the security chain ({@code
 * SecurityConfig} suppresses Spring Boot's container registration).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  static final String BEARER = "Bearer ";

  private final AuthProperties properties;
  private final JwtTokenService tokens;
  private final BiAppUserRepository users;

  private static UsernamePasswordAuthenticationToken authenticate(Caller caller, BiAppUser user) {
    List<SimpleGrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
    authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    return new UsernamePasswordAuthenticationToken(caller, null, authorities);
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String header = req.getHeader(HttpHeaders.AUTHORIZATION);
    if (properties.isEnabled()
        && header != null
        && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
        && SecurityContextHolder.getContext().getAuthentication() == null) {
      tokens
          .readAccessToken(header.substring(BEARER.length()).trim())
          // The user row, not the token, decides: a deleted account stops working at once, and a
          // role change applies on the next request. One primary-key lookup per request.
          .flatMap(caller -> users.findById(caller.userId()).map(user -> authenticate(caller, user)))
          .ifPresent(SecurityContextHolder.getContext()::setAuthentication);
    }
    chain.doFilter(req, res);
  }
}
