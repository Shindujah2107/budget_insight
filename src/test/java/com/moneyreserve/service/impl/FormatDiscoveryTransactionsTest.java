package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BiSmsFormatCandidate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two properties the concurrency and isolation of SMS format discovery depend on, which no H2
 * integration test can see (H2's job lock opens no outer transaction, and the tests do not race).
 */
@DisplayName("SMS format discovery: transaction and update guarantees")
class FormatDiscoveryTransactionsTest {

  @Test
  @DisplayName("maintenance runs in its own transaction, so a failure cannot undo the existing purges")
  void maintenanceIsRequiresNew() throws Exception {
    Transactional tx =
        FormatDiscoveryMaintenance.class
            .getMethod("run", OffsetDateTime.class)
            .getAnnotation(Transactional.class);
    assertNotNull(tx);
    assertEquals(Propagation.REQUIRES_NEW, tx.propagation());
  }

  @Test
  @DisplayName("candidate updates write only changed columns, so a stale read cannot overwrite a decision")
  void candidateUsesDynamicUpdate() {
    assertTrue(BiSmsFormatCandidate.class.isAnnotationPresent(DynamicUpdate.class));
  }
}
