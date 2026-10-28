package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code moneyreserve.insights.admin.config-write-enabled=false} freezes Configuration: every draft
 * write answers 403 MR.ADM.E.004, while reads keep working.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moneyreserve.insights.admin.config-write-enabled=false")
@ActiveProfiles("test")
@DisplayName("Budget Insights admin Configuration frozen")
class AdminConfigFrozenIT {

  private static final String CATEGORIES = "/api/insights/admin/config/categories";

  @Autowired private TestRestTemplate rest;

  private static HttpEntity<String> superAdmin(String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(TestTokens.bearer(UUID.randomUUID(), "SUPER_ADMIN"));
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return new HttpEntity<>(body, headers);
  }

  @Test
  @DisplayName("a draft write is refused with MR.ADM.E.004")
  void writesRefused() {
    ResponseEntity<String> response =
        rest.exchange(
            CATEGORIES,
            HttpMethod.POST,
            superAdmin(
                "{\"code\":\"GROCERIES\",\"nameEn\":\"Groceries\",\"iconKey\":\"ic_shopping\","
                    + "\"sortOrder\":18,\"active\":true}"),
            String.class);
    assertEquals(403, response.getStatusCode().value());
    assertTrue(response.getBody().contains("MR.ADM.E.004"), response.getBody());
  }

  @Test
  @DisplayName("reads keep working")
  void readsWork() {
    ResponseEntity<String> response =
        rest.exchange(CATEGORIES, HttpMethod.GET, superAdmin(null), String.class);
    assertEquals(200, response.getStatusCode().value());
  }
}
