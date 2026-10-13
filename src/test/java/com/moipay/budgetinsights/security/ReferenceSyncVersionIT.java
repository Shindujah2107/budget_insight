package com.moipay.budgetinsights.security;

import static com.moipay.budgetinsights.security.AdminTestSupport.data;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moipay.budgetinsights.repository.BiBankParserTemplateRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

/**
 * Handsets follow the reference release (architecture doc §1.5, §3.10): after a publish, the
 * version /reference/sync serves and the ingest stale check compares against is the new release,
 * even when no template changed (here: only a sender was added).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Reference sync follows the release version")
class ReferenceSyncVersionIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiBankParserTemplateRepository templates;

  @Test
  @DisplayName("a sender-only publish moves the version handsets sync to")
  void senderOnlyPublishMovesTheVersion() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    admin.config(
        HttpMethod.POST,
        "/senders",
        "{\"bankCode\":\"7056\",\"senderId\":\"SYNC" + System.nanoTime() % 100000
            + "\",\"active\":true}");
    var published = admin.publishAll();
    assertEquals(201, published.getStatusCode().value(), published.getBody());
    int version = data(published).path("version").asInt();

    assertEquals(version, templates.findCurrentTemplateVersion());
    assertEquals(
        version,
        data(admin.config(HttpMethod.GET, "/summary", null)).path("releaseVersion").asInt());
  }
}
