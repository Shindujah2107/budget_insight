package com.moipay.budgetinsights.service.jobs;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link JobLock} backed by {@code pg_try_advisory_xact_lock}.
 *
 * <p><b>Transaction-scoped, not session-scoped, and that choice is the whole safety argument.</b> A
 * session advisory lock is released by {@code pg_advisory_unlock} or when the connection closes.
 * Behind a connection pool the connection does not close, it returns to the pool — so a job that
 * throws before unlocking leaves the lock held by a pooled connection that is then handed to
 * unrelated work, and the job never runs again until the service restarts. A transaction lock is
 * released by PostgreSQL at commit or rollback, whichever happens, including when the JVM dies
 * mid-job. There is no code path that leaks it.
 *
 * <p>The consequence is that the job runs inside the lock's transaction. That is correct for the
 * jobs this service has — a bounded delete and a cache rebuild — and it means a job that fails half
 * way rolls back rather than leaving the work half done. It would be the wrong shape for a
 * long-running job, and that is the constraint to remember before adding one.
 *
 * <p>{@code try} rather than a blocking acquire: the second instance should discover immediately
 * that it has nothing to do and go back to sleep, not queue up behind the first and run the same
 * work a moment later.
 *
 * <p>{@code REQUIRES_NEW} so that the lock's lifetime is this method's, whatever the caller was
 * doing. A scheduled method has no ambient transaction today, but a future caller that does would
 * otherwise extend the lock to the end of its own transaction without saying so.
 */
@Slf4j
@RequiredArgsConstructor
public class PostgresAdvisoryJobLock implements JobLock {

  private final EntityManager entityManager;

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean runExclusively(String jobName, Runnable work) {
    // The one way this class can fail silently, closed. Everything above depends on the
    // @Transactional proxy actually being in place; without a transaction the lock is taken and
    // released at statement end, pg_try_advisory_xact_lock answers true to every caller, and both
    // instances run the job believing they hold it. That is the exact outcome this class exists to
    // prevent, and it leaves no trace. A bean constructed directly, a self-invocation, proxying
    // switched off -- any of them would produce it. Refusing to run makes it a loud failure that
    // the scheduler logs instead of a duplicate delete nobody sees.
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Insights job "
              + jobName
              + " asked for an advisory lock outside a transaction. The lock would be released at "
              + "statement end and every instance would run the job, so this refuses to run "
              + "instead. Call through the injected JobLock bean, not a bare instance.");
    }

    long key = keyFor(jobName);

    Boolean acquired =
        (Boolean)
            entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(:key)")
                .setParameter("key", key)
                .getSingleResult();

    if (!Boolean.TRUE.equals(acquired)) {
      log.info("Insights job {} is already running on another instance; skipping", jobName);
      return false;
    }

    log.info("Insights job {} starting on this instance", jobName);
    long started = System.nanoTime();
    try {
      work.run();
    } catch (RuntimeException ex) {
      log.error(
          "Insights job {} failed after {} ms",
          jobName,
          (System.nanoTime() - started) / 1_000_000,
          ex);
      throw ex;
    }
    log.info("Insights job {} finished in {} ms", jobName, (System.nanoTime() - started) / 1_000_000);
    return true;
  }

  /**
   * A stable 64-bit key for a job name.
   *
   * <p>The first eight bytes of the SHA-256, not {@code String.hashCode()}. The key is a global
   * namespace shared with anything else in the database that takes advisory locks, so it wants to
   * be wide and well distributed; a 32-bit Java string hash is neither, and its collisions are
   * findable by anyone who cares to look. The value must stay stable across restarts and across
   * instances, which rules out anything seeded per JVM.
   */
  static long keyFor(String jobName) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(jobName.getBytes(StandardCharsets.UTF_8));
      long key = 0L;
      for (int i = 0; i < 8; i++) {
        key = (key << 8) | (digest[i] & 0xffL);
      }
      return key;
    } catch (NoSuchAlgorithmException ex) {
      // SHA-256 is mandated by the JLS for every conformant JVM.
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
