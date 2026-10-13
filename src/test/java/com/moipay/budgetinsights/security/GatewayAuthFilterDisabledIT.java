package com.moipay.budgetinsights.security;

import static com.moipay.budgetinsights.security.FilterRegistrations.containerRegistrationsOfGatewayFilter;
import static com.moipay.budgetinsights.security.FilterRegistrations.describe;
import static com.moipay.budgetinsights.security.FilterRegistrations.securityChainOccurrences;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moipay.budgetinsights.config.GatewayAuthFilter;
import jakarta.servlet.ServletContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link GatewayAuthFilter} with {@code gateway-auth-enabled=false} — the claim the flag makes.
 *
 * <p>"Disabled" has to mean the filter does not run. Without a {@code FilterRegistrationBean} it
 * still runs on every request with the flag off; it merely runs outside the Spring Security chain,
 * which is not what anyone reading that configuration line would take it to mean. That is a
 * control that reads as off and is not, and it is the kind of gap a Gate 3 review is for.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moipay.budget-insights.security.gateway-auth-enabled=false")
@ActiveProfiles("test")
@DisplayName("GatewayAuthFilter registration — gateway auth disabled")
class GatewayAuthFilterDisabledIT {

  @Autowired private ServletWebServerApplicationContext context;
  @Autowired private FilterChainProxy securityFilterChain;

  @Test
  @DisplayName("The filter is not added to the Spring Security chain")
  void notInSecurityChain() {
    List<String> occurrences = securityChainOccurrences(securityFilterChain);
    assertTrue(
        occurrences.isEmpty(),
        "With the flag off SecurityConfig must not add the filter to the chain; found "
            + occurrences);
  }

  @Test
  @DisplayName("The filter does not execute as an independent servlet filter either")
  void notRegisteredWithTheContainer() {
    ServletContext servletContext = context.getServletContext();
    List<String> direct = containerRegistrationsOfGatewayFilter(servletContext);
    assertTrue(
        direct.isEmpty(),
        "gateway-auth-enabled is false, yet GatewayAuthFilter is still registered with the "
            + "servlet container as "
            + direct
            + " and will execute on every request. Container filters were:"
            + describe(servletContext));
  }
}
