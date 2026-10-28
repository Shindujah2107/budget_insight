package com.moneyreserve.service.jobs;

/**
 * Runs a scheduled job on exactly one instance.
 *
 * <p>Budget Insights runs two ECS tasks in every environment, and Spring's {@code @Scheduled} is
 * per-JVM: both tasks fire every job, at the same moment, against the same database. For a purge
 * that is a double delete; for an aggregate refresh it is two writers racing over the same cache
 * rows. Neither fails loudly, which is what makes it worth a control rather than a comment.
 *
 * <p>A PostgreSQL advisory lock was chosen over adding ShedLock: it is the same guarantee with no
 * new dependency, no new table and no new migration, and the database already in the request path
 * is the one arbitrating.
 */
public interface JobLock {

  /**
   * Runs {@code work} if this instance can take the lock named {@code jobName}.
   *
   * @param jobName stable identifier for the job; the lock is held against this name, so two jobs
   *     with the same name exclude each other and a rename releases the old lock's meaning
   * @param work the job body, run on the calling thread when the lock is taken
   * @return true if the work ran here, false if another instance holds the lock
   */
  boolean runExclusively(String jobName, Runnable work);
}
