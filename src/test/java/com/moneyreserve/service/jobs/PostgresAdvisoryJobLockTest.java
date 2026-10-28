package com.moneyreserve.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The advisory lock's decision, and the shape of the statement it makes it with.
 *
 * <p>What is mocked here is the database, so these tests prove the branch — that a refused lock
 * means the work does not run — and the exact SQL. They do not prove that PostgreSQL grants the
 * lock to one caller and refuses the other; that is a property of PostgreSQL, and it is proven
 * against a real PostgreSQL 16 server with two concurrent sessions, recorded in
 * {@code docs/R7_PHASE1_JOB_LOCK.md}. Saying which of the two a test covers matters more here than
 * usual: a mocked lock test that appeared to prove mutual exclusion would be worse than no test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PostgreSQL advisory job lock")
class PostgresAdvisoryJobLockTest {

  private static final String PURGE = "insights-raw-message-purge";
  private static final String REFRESH = "insights-aggregate-refresh";

  @Mock private EntityManager entityManager;
  @Mock private Query query;

  /**
   * The lock's own precondition, satisfied here the way the {@code @Transactional} proxy satisfies
   * it in production. These tests construct the bean directly, which is precisely the unproxied
   * case the class now refuses, so saying so explicitly is what keeps the other tests honest.
   */
  @BeforeEach
  void enterATransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @AfterEach
  void leaveTheTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private void lockAnswers(Boolean acquired) {
    when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(eq("key"), anyLong())).thenReturn(query);
    when(query.getSingleResult()).thenReturn(acquired);
  }

  @Test
  @DisplayName("runs the work and reports it ran when the lock is granted")
  void runsTheWorkWhenTheLockIsGranted() {
    lockAnswers(Boolean.TRUE);
    AtomicInteger runs = new AtomicInteger();

    boolean ran =
        new PostgresAdvisoryJobLock(entityManager)
            .runExclusively(PURGE, runs::incrementAndGet);

    assertTrue(ran);
    assertEquals(1, runs.get());
  }

  @Test
  @DisplayName("does not run the work when another instance holds the lock")
  void skipsTheWorkWhenTheLockIsRefused() {
    lockAnswers(Boolean.FALSE);
    AtomicInteger runs = new AtomicInteger();

    boolean ran =
        new PostgresAdvisoryJobLock(entityManager)
            .runExclusively(PURGE, runs::incrementAndGet);

    assertFalse(ran);
    assertEquals(0, runs.get(), "the job body ran on an instance that did not hold the lock");
  }

  @Test
  @DisplayName("takes a transaction-scoped lock, not a session-scoped one")
  void takesATransactionScopedLock() {
    lockAnswers(Boolean.TRUE);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

    new PostgresAdvisoryJobLock(entityManager).runExclusively(PURGE, () -> {});

    verify(entityManager).createNativeQuery(sql.capture());
    assertEquals("SELECT pg_try_advisory_xact_lock(:key)", sql.getValue());
  }

  @Test
  @DisplayName("locks against the key derived from the job name")
  void locksAgainstTheDerivedKey() {
    lockAnswers(Boolean.TRUE);
    ArgumentCaptor<Long> key = ArgumentCaptor.forClass(Long.class);

    new PostgresAdvisoryJobLock(entityManager).runExclusively(PURGE, () -> {});

    verify(query).setParameter(eq("key"), key.capture());
    assertEquals(2321604234852945982L, key.getValue());
  }

  @Test
  @DisplayName("refuses to run at all when there is no transaction to scope the lock to")
  void refusesOutsideATransaction() {
    // Without the transaction the advisory lock releases at statement end and answers true to
    // everyone, so the job would run on every instance with nothing to show for it. A throw is the
    // only outcome here that a scheduler can notice.
    TransactionSynchronizationManager.setActualTransactionActive(false);
    AtomicInteger runs = new AtomicInteger();

    assertThrows(
        IllegalStateException.class,
        () ->
            new PostgresAdvisoryJobLock(entityManager)
                .runExclusively(PURGE, runs::incrementAndGet));

    assertEquals(0, runs.get(), "the job ran without a lock that could have excluded anything");
  }

  /**
   * The literals below are the point of this test. The key is written into a database-wide
   * namespace; if the derivation changes, a rolling deploy has old tasks locking one key and new
   * tasks locking another, and for the length of the deploy the job has no protection at all.
   * Pinning the values makes that a failing build rather than a deployment-day surprise.
   */
  @Test
  @DisplayName("the key is stable across releases and distinct per job name")
  void theKeyIsStableAndDistinct() {
    assertEquals(2321604234852945982L, PostgresAdvisoryJobLock.keyFor(PURGE));
    assertEquals(-6413833002085187365L, PostgresAdvisoryJobLock.keyFor(REFRESH));
    assertNotEquals(
        PostgresAdvisoryJobLock.keyFor(PURGE), PostgresAdvisoryJobLock.keyFor(REFRESH));
  }
}
