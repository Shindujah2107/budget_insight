package com.moneyreserve.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.servers.Server;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Swagger UI sends "Try it out" to {@code servers[0]}. Left to springdoc, that entry is generated
 * from the Host header, which behind a proxy is an internal hostname the browser cannot reach.
 */
class OpenApiConfigServersTest {

  @Test
  @DisplayName("publishes the context path as the single, relative server URL")
  void publishesContextPathAsRelativeServer() {
    OpenAPI api = new OpenApiConfig("/insights").openAPI();

    assertThat(api.getServers()).extracting(Server::getUrl).containsExactly("/insights");
  }

  @Test
  @DisplayName("never publishes an absolute URL")
  void neverPublishesAnAbsoluteUrl() {
    String url = new OpenApiConfig("/insights").openAPI().getServers().get(0).getUrl();

    assertThat(url).startsWith("/").doesNotContain("://");
  }

  @Test
  @DisplayName("falls back to '/' when there is no context path (local mode)")
  void fallsBackToRootWithoutContextPath() {
    assertThat(new OpenApiConfig().openAPI().getServers())
        .extracting(Server::getUrl)
        .containsExactly("/");
  }

  @Test
  @DisplayName("normalises blank, null, missing-slash and trailing-slash context paths")
  void normalisesContextPath() {
    assertThat(OpenApiConfig.serverUrl(null)).isEqualTo("/");
    assertThat(OpenApiConfig.serverUrl("  ")).isEqualTo("/");
    assertThat(OpenApiConfig.serverUrl("/")).isEqualTo("/");
    assertThat(OpenApiConfig.serverUrl("insights")).isEqualTo("/insights");
    assertThat(OpenApiConfig.serverUrl("/insights/")).isEqualTo("/insights");
  }
}
