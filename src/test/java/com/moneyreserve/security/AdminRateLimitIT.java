package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/** Admin writes are rate limited per Super Admin (architecture doc §3.1); reads are not. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moneyreserve.insights.admin.writes-per-minute=3")
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: write rate limit")
class AdminRateLimitIT {

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("the 4th write in a minute gets 429 E.022 with Retry-After; reads still work")
  void writesAreLimited() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    for (int i = 0; i < 3; i++) {
      assertEquals(200, admin.config(HttpMethod.DELETE, "/changeset", null).getStatusCode().value());
    }
    ResponseEntity<String> limited = admin.config(HttpMethod.DELETE, "/changeset", null);
    assertEquals(429, limited.getStatusCode().value());
    assertEquals("MR.ADM.E.022", AdminTestSupport.json(limited).path("error").asText());
    assertNotNull(limited.getHeaders().getFirst("Retry-After"));
    assertEquals(200, admin.config(HttpMethod.GET, "/changeset", null).getStatusCode().value());
    AdminTestSupport other = new AdminTestSupport(rest, UUID.randomUUID());
    assertEquals(200, other.config(HttpMethod.DELETE, "/changeset", null).getStatusCode().value());
  }
}
