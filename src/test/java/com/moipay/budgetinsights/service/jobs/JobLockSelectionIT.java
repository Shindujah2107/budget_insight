package com.moipay.budgetinsights.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Which lock the context actually chose.
 *
 * <p>{@link com.moipay.budgetinsights.config.JobLockConfig} decides from the connected database, so
 * the only way to check it is to start a context and ask. Under the test profile that database is
 * H2, and the expected answer is the fallback — if this ever returned the PostgreSQL lock, every
 * scheduled job in the test suite would fail on a function H2 does not have.
 *
 * <p>The inverse case, a PostgreSQL context selecting the advisory lock, is not asserted here
 * because the build has no PostgreSQL to point at. It is covered by the session-level proof in
 * {@code docs/R7_PHASE1_JOB_LOCK.md} and, at startup, by the log line each branch writes.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Job lock selection")
class JobLockSelectionIT {

  @Autowired private JobLock jobLock;

  @Test
  @DisplayName("H2 gets the single-instance fallback, not the advisory lock")
  void h2GetsTheFallback() {
    assertEquals(SingleInstanceJobLock.class, AopProxyUtils.ultimateTargetClass(jobLock));
  }

  @Test
  @DisplayName("the wired lock runs work")
  void theWiredLockRunsWork() {
    AtomicInteger runs = new AtomicInteger();

    assertTrue(jobLock.runExclusively("job-lock-selection-it", runs::incrementAndGet));
    assertEquals(1, runs.get());
  }
}
