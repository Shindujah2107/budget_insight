package com.moipay.budgetinsights.service.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.moipay.budgetinsights.domain.BiDataExport;
import com.moipay.budgetinsights.repository.BiDataExportRepository;
import com.moipay.budgetinsights.repository.BiSmsFormatSampleRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * SMS format discovery joined the nightly retention purge. Whatever goes wrong in its part, the
 * purges that predate it — expired exports and raw messages — must still happen. Through the
 * scheduled entry point, so the job lock's transaction is in play (on PostgreSQL it is an outer
 * REQUIRES_NEW transaction that a joined failure would roll back).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Retention purge isolation from SMS format discovery")
class RetentionIsolationIT {

  @Autowired private InsightsRetentionJob job;
  @Autowired private BiDataExportRepository exports;
  @MockitoSpyBean private BiSmsFormatSampleRepository samples;

  @Test
  @DisplayName("a failing format-discovery pass does not undo the export purge")
  void failureIsIsolated() {
    doThrow(new IllegalStateException("simulated format-discovery failure"))
        .when(samples)
        .deleteExpired(any());
    UUID expired =
        exports
            .save(
                BiDataExport.builder()
                    .id(UUID.randomUUID())
                    .userId(UUID.randomUUID())
                    .tenantId(UUID.randomUUID())
                    .document("{\"schemaVersion\":1}")
                    .sizeBytes(20)
                    .requestedAt(OffsetDateTime.now().minusHours(48))
                    .expiresAt(OffsetDateTime.now().minusHours(1))
                    .build())
            .getId();

    job.purgeExpired();

    assertFalse(exports.findById(expired).isPresent(), "the export purge was undone");
  }
}
