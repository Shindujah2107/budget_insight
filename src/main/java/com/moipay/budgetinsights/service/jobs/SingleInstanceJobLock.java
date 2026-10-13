package com.moipay.budgetinsights.service.jobs;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link JobLock} for a database that has no advisory locks — in practice H2, which is to say tests
 * and a developer's laptop.
 *
 * <p>It excludes concurrent runs <b>within this JVM only</b>, which is the honest limit of what can
 * be promised without the database arbitrating. Two instances pointed at the same H2 file would
 * both run the job and neither would know. That is acceptable where it applies and nowhere else,
 * which is why {@link com.moipay.budgetinsights.config.JobLockConfig} logs a warning when it
 * selects this implementation: seeing that warning in a deployed environment means the deployment
 * is not on PostgreSQL and the jobs are unguarded.
 *
 * <p>The in-JVM exclusion is not decoration. Spring's default scheduler pool is single-threaded, so
 * today a job cannot overlap itself; a future pool size greater than one would change that quietly,
 * and a lock that behaves the same way in tests as in production is worth more than one that only
 * appears to.
 */
@Slf4j
public class SingleInstanceJobLock implements JobLock {

  private final Set<String> running = ConcurrentHashMap.newKeySet();

  @Override
  public boolean runExclusively(String jobName, Runnable work) {
    if (!running.add(jobName)) {
      log.info("Insights job {} is already running in this JVM; skipping", jobName);
      return false;
    }
    try {
      log.info("Insights job {} starting (single-instance lock)", jobName);
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
      log.info(
          "Insights job {} finished in {} ms", jobName, (System.nanoTime() - started) / 1_000_000);
      return true;
    } finally {
      running.remove(jobName);
    }
  }
}
