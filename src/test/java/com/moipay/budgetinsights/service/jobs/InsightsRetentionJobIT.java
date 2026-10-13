package com.moipay.budgetinsights.service.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moipay.budgetinsights.domain.BiDataExport;
import com.moipay.budgetinsights.domain.BiRawMessage;
import com.moipay.budgetinsights.domain.ParseStatus;
import com.moipay.budgetinsights.repository.BiDataExportRepository;
import com.moipay.budgetinsights.repository.BiRawMessageRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * BI-30 — what the retention purge deletes, and what it leaves alone.
 *
 * <p>The half that matters is the second one. A purge that deleted everything would satisfy any
 * test that only checked the expired rows had gone, and would quietly destroy a customer's export
 * the moment they requested it. So every case here seeds an expired row <i>and</i> a live one and
 * asserts on both.
 *
 * <p>Driven through {@code purgeNow()} rather than the cron. What is worth testing is which rows
 * go; reaching that through Spring's scheduler would be a test of Spring. The locked entry point is
 * exercised once, separately, to prove the wiring between the two is real.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Retention purge")
class InsightsRetentionJobIT {

  @Autowired private InsightsRetentionJob job;
  @Autowired private BiRawMessageRepository rawMessages;
  @Autowired private BiDataExportRepository exports;

  private final UUID user = UUID.randomUUID();
  private final UUID tenant = UUID.randomUUID();

  @Test
  @DisplayName("expired raw messages go and unexpired ones stay")
  void purgesExpiredRawMessagesOnly() {
    UUID expired = saveRawMessage(OffsetDateTime.now().minusDays(1));
    UUID live = saveRawMessage(OffsetDateTime.now().plusDays(7));

    job.purgeNow();

    assertFalse(rawMessages.findById(expired).isPresent(), "An expired raw message survived");
    assertTrue(
        rawMessages.findById(live).isPresent(),
        "The purge took a raw message that had not expired");
  }

  @Test
  @DisplayName("expired exports go and unexpired ones stay")
  void purgesExpiredExportsOnly() {
    UUID expired = saveExport(OffsetDateTime.now().minusHours(1));
    UUID live = saveExport(OffsetDateTime.now().plusHours(24));

    job.purgeNow();

    assertFalse(exports.findById(expired).isPresent(), "An expired export survived");
    assertTrue(
        exports.findById(live).isPresent(),
        "The purge deleted an export the customer could still download");
  }

  @Test
  @DisplayName("the scheduled entry point runs the same work through the lock")
  void theLockedEntryPointRuns() {
    // Proves purgeExpired() is wired to purgeNow() through the JobLock rather than being a method
    // that only looks like it does something. Under H2 the lock is the single-instance fallback,
    // so it grants and the work runs.
    UUID expired = saveExport(OffsetDateTime.now().minusHours(1));

    job.purgeExpired();

    assertFalse(
        exports.findById(expired).isPresent(),
        "The scheduled method did not reach the purge; the lock or the wiring is wrong");
  }

  // ---------------------------------------------------------------------------------

  private UUID saveRawMessage(OffsetDateTime ttlExpiresAt) {
    return rawMessages
        .save(
            BiRawMessage.builder()
                .id(UUID.randomUUID())
                .userId(user)
                .tenantId(tenant)
                .bankCode("RETENTION")
                .ciphertext("ENC::retention-fixture")
                .parseStatus(ParseStatus.UNPARSED)
                .receivedAt(OffsetDateTime.now().minusDays(30))
                .ttlExpiresAt(ttlExpiresAt)
                .build())
        .getId();
  }

  private UUID saveExport(OffsetDateTime expiresAt) {
    return exports
        .save(
            BiDataExport.builder()
                .id(UUID.randomUUID())
                .userId(user)
                .tenantId(tenant)
                .document("{\"schemaVersion\":1}")
                .sizeBytes(20)
                .requestedAt(OffsetDateTime.now().minusHours(48))
                .expiresAt(expiresAt)
                .build())
        .getId();
  }
}
