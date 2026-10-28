package com.moneyreserve.config;

import com.moneyreserve.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
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
   * The context path this service is mounted under (empty unless {@code server.servlet.context-path}
   * is set).
   *
   * <p>It becomes the one entry in the spec's {@code servers} list, as a <em>relative</em> URL, so
   * Swagger UI's "Try it out" requests go to whichever origin served the page — behind a proxy as
   * well as on localhost.
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
                .title("MoneyReserve API")
                .description(description())
                .version("1.0.0"))
        .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
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
            "Sign in with `POST /api/auth/register` or `/api/auth/login`, then `POST"
                + " /api/auth/verify` with the SMS code. Send the access token as `Authorization:"
                + " Bearer <token>`; refresh it with `POST /api/auth/refresh`. Every query is"
                + " scoped to the token's user. The admin API needs a SUPER_ADMIN user.\n\n");

    out.append("## Feature flags\n\n")
        .append(
            "`moneyreserve.insights.enabled` is the master switch. With it off every route"
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
        "\nEvery error carries this shape: `{ \"success\": false, \"code\": \"MR.…\","
            + " \"message\": \"…\" }`. The message is customer-facing and safe to display; the"
            + " code is what a client should branch on.\n");

    return out.toString();
  }
}
