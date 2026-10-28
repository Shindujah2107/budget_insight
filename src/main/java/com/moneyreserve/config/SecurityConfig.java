package com.moneyreserve.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.controller.AuthController;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * One way in: the bearer access token the app gets from {@code /api/auth/verify}.
 *
 * <p>Customers and staff sign in identically; the user's role decides what opens. The admin API
 * needs SUPER_ADMIN, every other route needs any signed-in user, and sign-in, health and the API
 * docs are public. Unauthenticated calls get a JSON 401 (the app's cue to refresh its token), and a
 * signed-in caller without the admin role gets a JSON 403 on admin routes.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;
  private final ObjectMapper objectMapper;
  private final AdminAccessDeniedHandler adminAccessDeniedHandler;

  private static final String[] ADMIN_ROUTES = {
    AdminRoles.BASE_PATH, AdminRoles.BASE_PATH + "/**"
  };

  /**
   * Browser origins allowed to call the API (Expo web). The phone app is a native client, sends no
   * {@code Origin} header, and is unaffected.
   */
  @Value("${moneyreserve.cors.allowed-origins:http://localhost:8081}")
  private String[] allowedOrigins;

  /**
   * A {@code Filter} that is also a {@code @Component} would be registered twice: once in the
   * security chain below and once by Spring Boot against the servlet container. This keeps the
   * chain as its only placement.
   */
  @Bean
  public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
    FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(c -> c.configurationSource(corsSource()))
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(h -> h.frameOptions(f -> f.sameOrigin()))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(adminAccessDeniedHandler)
                    .authenticationEntryPoint(
                        new AdminAuthenticationEntryPoint(
                            objectMapper, new BearerAuthenticationEntryPoint(objectMapper))))
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/info")
                    .permitAll()
                    .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers(AuthController.PUBLIC_ROUTES)
                    .permitAll()
                    .requestMatchers(ADMIN_ROUTES)
                    .hasRole(AdminRoles.SUPER_ADMIN)
                    .anyRequest()
                    .authenticated());
    return http.build();
  }

  /**
   * No username/password logins exist; this empty store stops Spring Boot from creating its default
   * user with a generated password.
   */
  @Bean
  public UserDetailsService userDetailsService() {
    return new InMemoryUserDetailsManager();
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
