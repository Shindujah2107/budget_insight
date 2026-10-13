package com.moipay.budgetinsights.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.common.dto.ApiError;
import com.moipay.budgetinsights.exception.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.stereotype.Component;

/**
 * A signed-in caller without {@code SUPER_ADMIN} on an admin route gets a JSON 403 with
 * {@code MOI.BI.ADM.E.001}. Every other path keeps Spring Security's default response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;
  private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
      throws IOException, ServletException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith(AdminRoles.BASE_PATH)) {
      log.info(
          "Budget Insights refused a caller without the required role on {} {}",
          request.getMethod(),
          request.getRequestURI());
      fallback.handle(request, response, denied);
      return;
    }
    log.info(
        "Budget Insights admin refused a caller without SUPER_ADMIN on {} {}",
        request.getMethod(),
        request.getRequestURI());
    ErrorCode code = ErrorCode.ADMIN_ROLE_REQUIRED;
    response.setStatus(code.getHttpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    objectMapper.writeValue(
        response.getOutputStream(),
        ApiError.of(
            code.getHttpStatus().value(),
            code.getCode(),
            code.getDefaultMessage(),
            request.getRequestURI(),
            null));
  }
}
