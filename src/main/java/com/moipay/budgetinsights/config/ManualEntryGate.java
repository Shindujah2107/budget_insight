package com.moipay.budgetinsights.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.common.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The R1 manual-entry release gate, applied before the request body is looked at.
 *
 * <p><b>Why an interceptor rather than a check inside the controller.</b> The gate began as the
 * first line of each write method, and {@code ManualFlagDisabledIT.createRefused} caught what that
 * actually produced: {@code 400}, not {@code 501}. Spring binds and validates a
 * {@code @Valid @RequestBody} before the method body runs, so a client sending anything the
 * validator disliked was told its payload was wrong by a feature that was switched off. A release
 * gate that only answers correctly for well-formed requests is not a rollback anyone can rely on —
 * a client cannot tell "this capability is withdrawn" from "my request was malformed", which is
 * the one distinction the 501 exists to make. {@code preHandle} runs before argument resolution,
 * so the gate now decides first and the body is never bound at all.
 *
 * <p><b>Both gates are decided here, in order.</b> The master switch first: with
 * {@code moipay.budget-insights.enabled} off the answer is 404, because "the Insights tab is
 * absent" and "the tab is here and this part is withdrawn" are different statements and the client
 * renders them differently. This interceptor originally stood aside for the master switch and left
 * that 404 to the controller, and {@code MasterSwitchIT} caught what that produced: {@code 400}.
 * The controller's guard sits behind the same {@code @Valid} binding, so with Budget Insights
 * switched off entirely a malformed body was answered by a validator belonging to a feature that
 * was supposed to be absent. Both gates therefore have to be decided before binding, not one of
 * them.
 *
 * <p>Registered only for the manual write routes — see {@code InsightsWebConfig}. The same
 * ordering applies to the R2, R3 and R4 gates, which are still checked inside their controllers
 * after validation; that is pre-existing behaviour and changing it is a separate decision.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualEntryGate implements HandlerInterceptor {

  private final InsightsProperties properties;
  private final ObjectMapper objectMapper;

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

    if (!properties.isEnabled()) {
      return refuse(response, HttpStatus.NOT_FOUND, "Budget Insights is not available.");
    }
    if (!properties.getManual().isEnabled()) {
      return refuse(
          response,
          HttpStatus.NOT_IMPLEMENTED,
          "Adding and editing transactions by hand is not available.");
    }
    return true;
  }

  private boolean refuse(HttpServletResponse response, HttpStatus status, String message)
      throws IOException {
    log.info("Manual transaction entry refused status={} reason=\"{}\"", status.value(), message);
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    objectMapper.writeValue(
        response.getWriter(),
        ApiResponse.<Void>builder().success(false).message(message).build());
    return false;
  }
}
