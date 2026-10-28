package com.moneyreserve.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The CORS allowlist, asserted as a browser would experience it.
 *
 * <p>This service previously ran {@code allowedOriginPatterns("*")} together with
 * {@code allowCredentials(true)}. Read together those two lines mean any site a customer visits
 * can call these endpoints with the customer's credentials attached, against a service holding
 * their bank transactions. Spring rejects the literal combination of {@code "*"} in
 * {@code allowedOrigins} with credentials; {@code allowedOriginPatterns} is the form that does not
 * get rejected, which is exactly why it is worth a test rather than a reading of the code.
 *
 * <p>The origins here are the localhost defaults. Real origins are supplied per environment
 * through {@code CORS_ALLOWED_ORIGINS} and are deliberately not written down in this repository.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("CORS policy")
class CorsPolicyIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("An allowlisted origin is permitted, and may send credentials")
  void allowlistedOriginPermitted() throws Exception {
    mockMvc
        .perform(
            options("/api/insights/summary")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
  }

  @Test
  @DisplayName("An origin outside the allowlist is refused")
  void unknownOriginRefused() throws Exception {
    // The whole point. Under the previous wildcard this returned 200 with the attacker's own
    // origin echoed back in Access-Control-Allow-Origin.
    mockMvc
        .perform(
            options("/api/insights/summary")
                .header("Origin", "https://not-an-approved-origin.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("A near-miss on the allowlisted origin is still refused")
  void nearMissRefused() throws Exception {
    // Guards against someone reintroducing a pattern with a wildcard in it. A host that merely
    // contains an approved origin as a substring must not match.
    mockMvc
        .perform(
            options("/api/insights/summary")
                .header("Origin", "http://localhost:3000.attacker.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("The second allowlisted origin is permitted too, so the list is a list")
  void secondOriginPermitted() throws Exception {
    // Without this, a configuration that hard-coded one origin would pass every other test here.
    mockMvc
        .perform(
            options("/api/insights/summary")
                .header("Origin", "http://localhost:8083")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8083"));
  }
}
