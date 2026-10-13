package com.moipay.budgetinsights.config;

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
 * The published spec and Swagger UI config must point the browser at CloudFront, not at the API
 * Gateway.
 *
 * <p>Every request is made to look as it does in DEV: arriving with the gateway's execute-api
 * hostname, which is what CloudFront forwards as Host. Before the fix springdoc echoed that host
 * into {@code servers[0]} ("Generated server url") and into {@code oauth2RedirectUrl}, so Swagger
 * UI sent "Try it out" straight to the gateway, where origin verification answers 403 without CORS
 * headers and the browser reports "Failed to fetch".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "server.servlet.context-path=/insights",
    "springdoc.api-docs.path=/v3/api-docs/openapi",
    "springdoc.swagger-ui.enabled=true",
    "springdoc.swagger-ui.oauth2-redirect-url=" + ApiDocsServers_IntegrationTest.OAUTH2_REDIRECT
})
class ApiDocsServers_IntegrationTest {

  static final String OAUTH2_REDIRECT =
      "https://djo2pw7molyaz.cloudfront.net/insights/swagger-ui/oauth2-redirect.html";

  private static final String GATEWAY_HOST = "91dekuv6yh.execute-api.us-east-1.amazonaws.com";

  @Autowired
  private MockMvc mockMvc;

  /** Host as API Gateway presents it to the service in DEV. */
  private static RequestPostProcessor viaGatewayHost() {
    return request -> {
      request.setScheme("https");
      request.setSecure(true);
      request.setServerName(GATEWAY_HOST);
      request.setServerPort(443);
      return request;
    };
  }

  @Test
  @DisplayName("servers is the relative module path, whatever host the request arrived on")
  void specServersIsRelativeModulePath() throws Exception {
    mockMvc.perform(get("/insights/v3/api-docs/openapi").contextPath("/insights").with(viaGatewayHost()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.servers.length()").value(1))
        .andExpect(jsonPath("$.servers[0].url").value("/insights"))
        .andExpect(content().string(not(containsString("execute-api"))));
  }

  @Test
  @DisplayName("swagger-config carries the configured CloudFront OAuth2 redirect, not the gateway host")
  void swaggerConfigOauth2RedirectIsCloudFront() throws Exception {
    mockMvc.perform(get("/insights/v3/api-docs/openapi/swagger-config").contextPath("/insights").with(viaGatewayHost()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauth2RedirectUrl").value(OAUTH2_REDIRECT))
        .andExpect(jsonPath("$.url").value("/insights/v3/api-docs/openapi"))
        .andExpect(content().string(not(containsString("execute-api"))));
  }
}
