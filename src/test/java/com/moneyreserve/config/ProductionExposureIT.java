package com.moneyreserve.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * What a deployed server shows to anyone, by default: health only. API docs and Swagger UI are off
 * unless API_DOCS_ENABLED=true; actuator info, env and metrics are closed; secrets have no
 * built-in values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Production exposure defaults")
class ProductionExposureIT {

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("health answers; API docs, Swagger UI and other actuator endpoints don't")
  void onlyHealthIsOpen() {
    assertEquals(200, rest.getForEntity("/actuator/health", String.class).getStatusCode().value());
    for (String path : new String[] {"/v3/api-docs", "/swagger-ui.html", "/swagger-ui/index.html", "/actuator/info", "/actuator/env", "/actuator/metrics"}) {
      ResponseEntity<String> r = rest.getForEntity(path, String.class);
      assertTrue(r.getStatusCode().value() == 404 || r.getStatusCode().value() == 401 || r.getStatusCode().value() == 403, path + " → " + r.getStatusCode());
    }
  }

  @Test
  @DisplayName("every secret comes from the environment with no built-in value (application.yml)")
  void secretsHaveNoDefaults() throws Exception {
    String yml = Files.readString(Path.of("src/main/resources/application.yml"));
    for (String secret : new String[] {"DB_PASSWORD", "BI_JWT_SECRET", "DEVICE_HASH_SALT", "FORMAT_USER_HASH_KEY", "TEXTLK_API_TOKEN", "TEXTLK_SENDER_ID", "SUPER_ADMIN_PHONES"}) {
      assertTrue(yml.contains("${" + secret + ":}"), secret + " must default to empty, never to a value");
    }
    assertTrue(yml.contains("enabled: ${API_DOCS_ENABLED:false}"), "API docs off unless switched on");
  }
}
