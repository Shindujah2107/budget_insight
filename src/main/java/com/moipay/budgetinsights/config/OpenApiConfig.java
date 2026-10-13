package com.moipay.budgetinsights.config;

import com.moipay.budgetinsights.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The published contract (BI-31).
 *
 * <p>The error catalogue is <b>generated from {@link ErrorCode}</b> rather than written out. A
 * hand-maintained table is correct on the day it is written and wrong from the first code added
 * afterwards, and a client integrating against a stale error list finds out in production. This one
 * cannot drift: adding an enum constant adds a row.
 */
@Configuration
public class OpenApiConfig {

  /**
   * The context path this service is mounted under ({@code /insights} on AWS, empty locally).
   *
   * <p>It becomes the one entry in the spec's {@code servers} list, as a <em>relative</em> URL.
   * Without an explicit entry springdoc generates one from the request's Host header, and on AWS
   * that host is the API Gateway's {@code execute-api} hostname: CloudFront forwards to the gateway
   * with the origin's Host. Swagger UI sends every "Try it out" request to {@code servers[0]}, so
   * they all went straight to the gateway, where origin verification rejects them by design — a
   * 403 without CORS headers, which the browser reports as "Failed to fetch". A relative URL
   * resolves against whichever origin served the page: CloudFront on AWS, localhost locally. This
   * is the approach kyc-engine already takes ({@code servers: [{url: /kyc}]}).
   */
  private final String contextPath;

  public OpenApiConfig() {
    this("");
  }

  @Autowired
  public OpenApiConfig(@Value("${server.servlet.context-path:}") String contextPath) {
    this.contextPath = contextPath;
  }

  /** {@code /insights} stays {@code /insights}; empty, {@code /} or null become {@code /}. */
  static String serverUrl(String contextPath) {
    if (contextPath == null || contextPath.isBlank()) {
      return "/";
    }
    String path = contextPath.trim();
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  @Bean
  public OpenAPI openAPI() {
    return new OpenAPI()
        .servers(List.of(new Server().url(serverUrl(contextPath))))
        .info(
            new Info()
                .title("MoiPay Budget Insights")
                .description(description())
                .version("1.0.0")
                .contact(new Contact().name("MoiPay Platform Team").email("platform@moipay.lk")))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"))
                .addSecuritySchemes(
                    "basicAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description(
                            "Direct (non-gateway) mode only. Credentials are supplied per "
                                + "environment through BUDGET_INSIGHTS_ADMIN_USERNAME and "
                                + "BUDGET_INSIGHTS_ADMIN_PASSWORD and are not published here. "
                                + "Every AWS environment runs behind the gateway, where this "
                                + "scheme does not apply.")));
  }

  private String description() {
    StringBuilder out = new StringBuilder();

    out.append(
        "Monthly budget, payment activity and spending breakdown. Transactions are referenced by"
            + " UUID and carry a masked instrument tail of three or four digits at most — no message content,"
            + " no full account number, and no customer display data is stored or returned by"
            + " this service.\n\n");

    out.append("## Authentication\n\n")
        .append(
            "The gateway validates the customer's JWT, strips `Authorization`, and injects"
                + " `X-Gateway-Token`, `X-User-Id` and `X-Tenant`. This service never sees a JWT"
                + " and has no scopes. The token proves the request came through the gateway,"
                + " `X-User-Id` says who it is for, and every repository query is scoped by that"
                + " id. Both halves are load-bearing: a valid token with no `X-User-Id` is"
                + " refused, and so is an `X-User-Id` with no token.\n\n");

    out.append("## Feature flags\n\n")
        .append(
            "`moipay.budget-insights.enabled` is the master switch. With it off every route"
                + " answers **404**, as though the feature were not in the build. Each release"
                + " has its own child flag, and with a child flag off its routes answer **501**."
                + " The two are different promises — 404 says the feature is absent and the"
                + " client should hide the tab; 501 says the tab is there and one capability"
                + " inside it is withdrawn.\n\n");

    out.append("## Ownership\n\n")
        .append(
            "Another customer's row answers **404**, never 403. Ownership scoping means the row"
                + " does not exist as far as this caller's queries are concerned, and a 403 would"
                + " confirm that it does — which is itself a disclosure.\n\n");

    out.append("## Error catalogue\n\n")
        .append("| Code | HTTP | Meaning |\n| --- | --- | --- |\n");
    for (ErrorCode code : ErrorCode.values()) {
      out.append("| `")
          .append(code.name())
          .append("` | ")
          .append(code.getHttpStatus().value())
          .append(" | ")
          .append(code.getDefaultMessage())
          .append(" |\n");
    }
    out.append(
        "\nEvery error carries this shape: `{ \"success\": false, \"code\": \"MOI.BI.…\","
            + " \"message\": \"…\" }`. The message is customer-facing and safe to display; the"
            + " code is what a client should branch on.\n");

    return out.toString();
  }
}
