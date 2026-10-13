package com.moipay.budgetinsights.security;

import com.moipay.budgetinsights.config.GatewayAuthFilter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Where a filter is actually registered, asked of a running container rather than argued from
 * source.
 *
 * <p>The distinction these helpers draw is the whole point of the tests that use them. A servlet
 * <b>container registration</b> is a filter the container invokes itself, outside Spring
 * Security's control. <b>Security chain membership</b> is a filter inside {@link FilterChainProxy},
 * which is where an authentication filter belongs. A filter can be in both at once — that is
 * precisely the defect these tests were written to catch — and reading either class on its own
 * does not reveal it.
 */
final class FilterRegistrations {

  private FilterRegistrations() {}

  /** Container-level registration names whose filter class is {@link GatewayAuthFilter}. */
  static List<String> containerRegistrationsOfGatewayFilter(ServletContext servletContext) {
    List<String> found = new ArrayList<>();
    Map<String, ? extends FilterRegistration> registrations =
        servletContext.getFilterRegistrations();
    for (Map.Entry<String, ? extends FilterRegistration> entry : registrations.entrySet()) {
      if (GatewayAuthFilter.class.getName().equals(entry.getValue().getClassName())) {
        found.add(entry.getKey());
      }
    }
    return found;
  }

  /** Every occurrence of the filter across every chain inside {@link FilterChainProxy}. */
  static List<String> securityChainOccurrences(FilterChainProxy proxy) {
    List<String> found = new ArrayList<>();
    for (SecurityFilterChain chain : proxy.getFilterChains()) {
      for (jakarta.servlet.Filter filter : chain.getFilters()) {
        if (filter instanceof GatewayAuthFilter) {
          found.add(filter.getClass().getSimpleName());
        }
      }
    }
    return found;
  }

  /** Every container filter, for a failure message that shows what was actually there. */
  static String describe(ServletContext servletContext) {
    return servletContext.getFilterRegistrations().entrySet().stream()
        .map(e -> e.getKey() + " -> " + e.getValue().getClassName())
        .sorted()
        .collect(Collectors.joining("\n  ", "\n  ", ""));
  }
}
