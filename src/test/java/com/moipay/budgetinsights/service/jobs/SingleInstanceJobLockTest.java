package com.moipay.budgetinsights.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The fallback lock used under H2.
 *
 * <p>It promises exclusion within one JVM and nothing wider, so that is exactly what is tested
 * here: two threads, one job name, one execution. The test is written with real threads rather than
 * a re-entrant call because the failure it guards against is a scheduler pool size greater than
 * one, and a re-entrant call would pass against an implementation that only worked on one thread.
 */
@DisplayName("Single-instance job lock")
class SingleInstanceJobLockTest {

  private static final String JOB = "insights-raw-message-purge";

  @Test
  @Timeout(10)
  @DisplayName("a second thread is refused while the job is running")
  void aSecondThreadIsRefusedWhileTheJobIsRunning() throws Exception {
    SingleInstanceJobLock lock = new SingleInstanceJobLock();
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger runs = new AtomicInteger();
    AtomicBoolean holderRan = new AtomicBoolean();

    Thread holder =
        new Thread(
            () ->
                holderRan.set(
                    lock.runExclusively(
                        JOB,
                        () -> {
                          runs.incrementAndGet();
                          started.countDown();
                          try {
                            release.await(5, TimeUnit.SECONDS);
                          } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                          }
                        })));
    holder.start();
    assertTrue(started.await(5, TimeUnit.SECONDS), "the first thread never entered the job");

    boolean secondRan = lock.runExclusively(JOB, runs::incrementAndGet);

    release.countDown();
    holder.join(5_000);

    assertFalse(secondRan, "a second thread ran the job while the first still held the lock");
    assertTrue(holderRan.get());
    assertEquals(1, runs.get(), "the job body ran more than once");
  }

  @Test
  @Timeout(10)
  @DisplayName("the lock is released when the job throws, not only when it succeeds")
  void theLockIsReleasedWhenTheJobThrows() {
    SingleInstanceJobLock lock = new SingleInstanceJobLock();

    // The lock must not swallow it either: a failed job has to reach the scheduler's handler.
    assertThrows(
        IllegalStateException.class,
        () ->
            lock.runExclusively(
                JOB,
                () -> {
                  throw new IllegalStateException("job failed");
                }));

    AtomicInteger runs = new AtomicInteger();
    assertTrue(
        lock.runExclusively(JOB, runs::incrementAndGet),
        "a job that threw left its own lock held, so it would never run again");
    assertEquals(1, runs.get());
  }

  @Test
  @DisplayName("different job names do not exclude each other")
  void differentJobNamesDoNotExcludeEachOther() {
    SingleInstanceJobLock lock = new SingleInstanceJobLock();
    AtomicInteger inner = new AtomicInteger();

    boolean ran =
        lock.runExclusively(
            JOB, () -> lock.runExclusively("insights-aggregate-refresh", inner::incrementAndGet));

    assertTrue(ran);
    assertEquals(1, inner.get());
  }
}
