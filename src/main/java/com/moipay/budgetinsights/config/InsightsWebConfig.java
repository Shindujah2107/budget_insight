package com.moipay.budgetinsights.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Where release gates that must run before request-body binding are attached.
 *
 * <p>Only {@link ManualEntryGate} for now, on the three manual write routes. Both path patterns
 * are needed: {@code /api/insights/transactions} is the create, and
 * {@code /api/insights/transactions/**} covers the edit and the delete.
 *
 * <p>Deliberately not a blanket registration over {@code /api/insights/**}. The manual gate would
 * then also intercept reads, which it must never do — withdrawing entry by hand must not stop a
 * customer seeing the transactions they already have.
 */
@Configuration
@RequiredArgsConstructor
public class InsightsWebConfig implements WebMvcConfigurer {

  private final ManualEntryGate manualEntryGate;
  private final AdminAccessGuard adminAccessGuard;
  private final InsightsRequestLogger requestLogger;

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(manualEntryGate)
        .addPathPatterns("/api/insights/transactions", "/api/insights/transactions/**");
    registry
        .addInterceptor(adminAccessGuard)
        .addPathPatterns(AdminRoles.BASE_PATH, AdminRoles.BASE_PATH + "/**");
    // One log line per customer request; admin requests are logged by AdminAccessGuard.
    registry
        .addInterceptor(requestLogger)
        .addPathPatterns("/api/insights/**")
        .excludePathPatterns(AdminRoles.BASE_PATH, AdminRoles.BASE_PATH + "/**");
  }
}
