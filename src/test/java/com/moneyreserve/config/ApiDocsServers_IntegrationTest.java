package com.moneyreserve.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The published spec must not echo the request's Host header into {@code servers[0]}: behind a
 * proxy that is an internal hostname, and Swagger UI would send "Try it out" there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "server.servlet.context-path=/insights",
    "springdoc.api-docs.path=/v3/api-docs/openapi",
    "springdoc.swagger-ui.enabled=true"
})
class ApiDocsServers_IntegrationTest {

  private static final String INTERNAL_HOST = "internal.example.test";

  @Autowired
  private MockMvc mockMvc;

  /** A request that arrives with a proxy's internal hostname. */
  private static RequestPostProcessor viaInternalHost() {
    return request -> {
      request.setScheme("https");
      request.setSecure(true);
      request.setServerName(INTERNAL_HOST);
      request.setServerPort(443);
      return request;
    };
  }

  @Test
  @DisplayName("servers is the relative module path, whatever host the request arrived on")
  void specServersIsRelativeModulePath() throws Exception {
    mockMvc.perform(get("/insights/v3/api-docs/openapi").contextPath("/insights").with(viaInternalHost()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servers.length()").value(1))
        .andExpect(jsonPath("$.servers[0].url").value("/insights"))
        .andExpect(content().string(not(containsString(INTERNAL_HOST))));
  }

  @Test
  @DisplayName("swagger-config points at the relative spec path")
  void swaggerConfigUsesRelativeSpecPath() throws Exception {
    mockMvc.perform(get("/insights/v3/api-docs/openapi/swagger-config").contextPath("/insights").with(viaInternalHost()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.url").value("/insights/v3/api-docs/openapi"));
  }
}
