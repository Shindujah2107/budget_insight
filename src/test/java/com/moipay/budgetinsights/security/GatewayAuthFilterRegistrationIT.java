package com.moipay.budgetinsights.security;

import static com.moipay.budgetinsights.security.FilterRegistrations.containerRegistrationsOfGatewayFilter;
import static com.moipay.budgetinsights.security.FilterRegistrations.describe;
import static com.moipay.budgetinsights.security.FilterRegistrations.securityChainOccurrences;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@link GatewayAuthFilter} registration with {@code gateway-auth-enabled=true}.
 *
 * <p><b>This test exists because the filter's own javadoc was wrong.</b> It claimed the filter is
 * "active when gateway-auth-enabled=true", and the class carried nothing but {@code @Component}.
 * Spring Boot auto-registers any {@code Filter} bean against the servlet container at {@code /*}
 * unless a {@code FilterRegistrationBean} says otherwise, so the filter was registered twice with
 * the flag on, and still registered with it off. Neither fact is visible from reading
 * {@code GatewayAuthFilter} or {@code SecurityConfig}, which is why it is asserted here against a
 * started container instead of reasoned about.
 *
 * <p>The required end state is one registration, inside the Spring Security chain, present only
 * when the flag is on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("GatewayAuthFilter registration — gateway auth enabled")
class GatewayAuthFilterRegistrationIT {

  @Autowired private ServletWebServerApplicationContext context;
  @Autowired private FilterChainProxy securityFilterChain;

  @Test
  @DisplayName("The filter appears in the Spring Security chain exactly once")
  void inChainExactlyOnce() {
    List<String> occurrences = securityChainOccurrences(securityFilterChain);
    assertEquals(
        1,
        occurrences.size(),
        "GatewayAuthFilter must appear exactly once in the Spring Security chain; found "
            + occurrences);
  }

  @Test
  @DisplayName("The filter is not separately registered against the servlet container")
  void notRegisteredWithTheContainerAsWell() {
    ServletContext servletContext = context.getServletContext();
    List<String> direct = containerRegistrationsOfGatewayFilter(servletContext);
    assertTrue(
        direct.isEmpty(),
        "GatewayAuthFilter is registered directly with the servlet container as "
            + direct
            + ", so it executes a second time outside Spring Security's control. A Filter bean "
            + "needs a FilterRegistrationBean with setEnabled(false) to stop Spring Boot "
            + "auto-registering it. Container filters were:"
            + describe(servletContext));
  }

  @Test
  @DisplayName("The security chain is registered, so something is still authenticating")
  void securityChainStillRegistered() {
    // Guards the opposite mistake. Suppressing the auto-registration while failing to add the
    // filter to the chain would leave nothing authenticating at all, and every other test here
    // would still be green.
    ServletContext servletContext = context.getServletContext();
    assertTrue(
        servletContext.getFilterRegistrations().containsKey("springSecurityFilterChain"),
        "springSecurityFilterChain must be registered with the container:"
            + describe(servletContext));
  }
}
