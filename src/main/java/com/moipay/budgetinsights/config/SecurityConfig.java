package com.moipay.budgetinsights.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final GatewayAuthFilter gatewayAuthFilter;
  private final ObjectMapper objectMapper;
  private final AdminAccessDeniedHandler adminAccessDeniedHandler;

  private static final String[] ADMIN_ROUTES = {
    AdminRoles.BASE_PATH, AdminRoles.BASE_PATH + "/**"
  };

  /**
   * The HTTP Basic account, used only on the non-gateway path.
   *
   * <p>No default. These previously carried literal fallback values, which meant any run without
   * an AWS profile active came up with a known username and password over
   * {@code anyRequest().authenticated()} — the profile was the only thing standing between a
   * misconfigured container and a working login, and the same pair was printed in the Swagger
   * page's security scheme description. A blank value now produces an account nobody can
   * authenticate as, which is the fail-closed direction.
   */
  @Value("${moipay.budget-insights.admin.username:}")
  private String adminUsername;

  @Value("${moipay.budget-insights.admin.password:}")
  private String adminPassword;

  @Value("${moipay.budget-insights.security.gateway-auth-enabled:false}")
  private boolean gatewayAuthEnabled;

  /**
   * Browser origins permitted to call this service with credentials.
   *
   * <p>Localhost only by default, matching FeeEngine and CaseEngine. This was previously
   * {@code allowedOriginPatterns("*")} together with {@code allowCredentials(true)}: any site a
   * customer visited could call these endpoints with the customer's cookies attached, against a
   * service holding personal financial detail. Real origins belong on the task definition through
   * {@code CORS_ALLOWED_ORIGINS}, not in this repository.
   *
   * <p>The mobile app is unaffected either way. It is a native client, sends no {@code Origin}
   * header, and CORS is a browser control that never applies to it.
   */
  @Value("${moipay.cors.allowed-origins:http://localhost:3000,http://localhost:8083}")
  private String[] allowedOrigins;

  /**
   * Stops Spring Boot registering {@link GatewayAuthFilter} against the servlet container.
   *
   * <p><b>A {@code Filter} that is also a {@code @Component} is registered twice.</b> Once here,
   * where {@code SecurityConfig} puts it in the chain, and once by Spring Boot's own
   * auto-registration at {@code /*} — which happens whatever {@code gateway-auth-enabled} says,
   * because that flag is only consulted below. {@code GatewayAuthFilterRegistrationIT} and
   * {@code GatewayAuthFilterDisabledIT} assert both halves of this against a started container;
   * before this bean existed they failed, printing {@code gatewayAuthFilter} in the container's
   * own filter list with the flag both on and off.
   *
   * <p>Two consequences, neither visible from reading either class. With the flag on the filter ran
   * twice per request, the second time innermost — after Spring Security had made its authorization
   * decision and cleared the context, leaving a principal on a pooled thread that nothing then
   * cleared. With the flag off it still ran, so "disabled" did not mean the filter does not
   * execute, which is the only thing that configuration line can reasonably be read to promise.
   *
   * <p>The bean stays a {@code @Component} so the chain can inject it; {@code setEnabled(false)}
   * suppresses only the container registration.
   */
  @Bean
  public FilterRegistrationBean<GatewayAuthFilter> gatewayAuthFilterRegistration(
      GatewayAuthFilter filter) {
    FilterRegistrationBean<GatewayAuthFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(c -> c.configurationSource(corsSource()))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(h -> h.frameOptions(f -> f.sameOrigin()))
        .exceptionHandling(e -> e.accessDeniedHandler(adminAccessDeniedHandler));

    if (gatewayAuthEnabled) {
      // Unauthenticated: admin routes answer a JSON 401; every other route keeps its 403.
      http.exceptionHandling(
          e ->
              e.authenticationEntryPoint(
                  new AdminAuthenticationEntryPoint(objectMapper, new Http403ForbiddenEntryPoint())));
      http.addFilterBefore(gatewayAuthFilter, UsernamePasswordAuthenticationFilter.class)
          .authorizeHttpRequests(
              auth ->
                  auth.requestMatchers("/actuator/health", "/actuator/info")
                      .permitAll()
                      .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                      .permitAll()
                      // Budget Insights admin: Super Admin only. TENANT_ADMIN (held by every
                      // self-registered customer) and the Basic account's ADMIN are not enough.
                      .requestMatchers(ADMIN_ROUTES)
                      .hasRole(AdminRoles.SUPER_ADMIN)
                      .anyRequest()
                      .authenticated());
    } else {
      http.authorizeHttpRequests(
              auth ->
                  auth.requestMatchers("/actuator/health", "/actuator/info")
                      .permitAll()
                      .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                      .permitAll()
                      // Budget Insights admin: Super Admin only. TENANT_ADMIN (held by every
                      // self-registered customer) and the Basic account's ADMIN are not enough.
                      .requestMatchers(ADMIN_ROUTES)
                      .hasRole(AdminRoles.SUPER_ADMIN)
                      .anyRequest()
                      .authenticated())
          .httpBasic(basic -> basic.authenticationEntryPoint(basicEntryPoint()))
          .exceptionHandling(e -> e.authenticationEntryPoint(basicEntryPoint()));
    }

    return http.build();
  }

  /** HTTP Basic's usual challenge, except on admin routes (JSON 401, no challenge). */
  private AuthenticationEntryPoint basicEntryPoint() {
    BasicAuthenticationEntryPoint basic = new BasicAuthenticationEntryPoint();
    basic.setRealmName("Realm");
    return new AdminAuthenticationEntryPoint(objectMapper, basic);
  }

  /**
   * The HTTP Basic account for the non-gateway path.
   *
   * <p>With no username or password configured the account is created with a random password that
   * is generated here and never recorded anywhere — no log line, no field, nothing to recover. The
   * effect is an account that cannot be authenticated as, which is what a service with no
   * credentials configured should offer. Set {@code BUDGET_INSIGHTS_ADMIN_USERNAME} and
   * {@code BUDGET_INSIGHTS_ADMIN_PASSWORD} to use it.
   *
   * <p>Deliberately not a startup failure. Every AWS profile runs with
   * {@code gateway-auth-enabled=true}, where this account is unreachable regardless, and refusing
   * to boot over an unused account would turn a hardening change into an outage.
   */
  @Bean
  public UserDetailsService userDetailsService(PasswordEncoder encoder) {
    boolean configured =
        adminUsername != null
            && !adminUsername.isBlank()
            && adminPassword != null
            && !adminPassword.isBlank();

    if (!configured) {
      log.info(
          "No HTTP Basic credentials are configured "
              + "(moipay.budget-insights.admin.username / .password). The account has been given "
              + "a random password that is not recorded, so Basic authentication cannot succeed. "
              + "This is expected wherever gateway-auth-enabled is true.");
    }

    String username = configured ? adminUsername : "disabled";
    String password = configured ? adminPassword : UUID.randomUUID().toString();

    return new InMemoryUserDetailsManager(
        User.builder().username(username).password(encoder.encode(password)).roles("ADMIN").build());
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public CorsConfigurationSource corsSource() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.setAllowedOriginPatterns(Arrays.asList(allowedOrigins));
    cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    cfg.setAllowedHeaders(List.of("*"));
    cfg.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
    src.registerCorsConfiguration("/**", cfg);
    return src;
  }
}
