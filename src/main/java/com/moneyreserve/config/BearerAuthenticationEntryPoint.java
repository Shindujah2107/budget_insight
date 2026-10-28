package com.moneyreserve.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.common.dto.ApiError;
import com.moneyreserve.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Every unauthenticated call gets a JSON 401 (code {@code MR.AUT.E.008}) with {@code
 * WWW-Authenticate: Bearer} — the app's signal to refresh its token, or sign the customer out.
 */
public class BearerAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public BearerAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException, ServletException {
    ErrorCode code = ErrorCode.AUTH_SESSION_EXPIRED;
    response.setStatus(code.getHttpStatus().value());
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ApiError.of(
            code.getHttpStatus().value(),
            code.getCode(),
            code.getDefaultMessage(),
            request.getRequestURI()));
  }
}
