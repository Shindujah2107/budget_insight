package com.moneyreserve.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.common.dto.ApiError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * An unauthenticated call (no valid access token) to an admin route gets a JSON 401
 * (architecture doc §3.1: "no token → 401"). Every other path keeps the response it had before
 * the admin API existed, through {@code fallback}.
 */
@Slf4j
public class AdminAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;
  private final AuthenticationEntryPoint fallback;

  public AdminAuthenticationEntryPoint(ObjectMapper objectMapper, AuthenticationEntryPoint fallback) {
    this.objectMapper = objectMapper;
    this.fallback = fallback;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException, ServletException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith(AdminRoles.BASE_PATH)) {
      log.info(
          "Budget Insights refused an unauthenticated call on {} {}",
          request.getMethod(),
          request.getRequestURI());
      fallback.commence(request, response, ex);
      return;
    }
    log.info(
        "Budget Insights admin refused an unauthenticated call on {} {}",
        request.getMethod(),
        request.getRequestURI());
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ApiError.of(
            401, "Unauthorized", "Sign in as a Super Admin.", request.getRequestURI(), null));
  }
}
