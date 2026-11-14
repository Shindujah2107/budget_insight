package com.moneyreserve.service.jobs;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiJobCursor;
import com.moneyreserve.repository.BiJobCursorRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.BillService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bills radar, once a day: re-checks every customer with a bill due soon (or overdue) so "due
 * soon" and "missed?" alerts arrive even on days they record no payment. One customer's failure
 * does not stop the rest. Runs on one task only ({@link JobLock}).
 *
 * <p>Built for many customers (V1.24.0): customers are read in pages ({@code daily-job-page-size},
 * in id order), those whose bills are all due more than {@code daily-job-due-within-days} away are
 * skipped (a new payment still re-checks them at once), and a run stops after
 * {@code daily-job-time-limit}, saving where it stopped in {@code bi_job_cursor}. The cron fires
 * every 30 minutes through the morning; a run on a day already completed does nothing, and one
 * after a stop continues from the saved customer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "moneyreserve.insights.jobs",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BillsDailyJob {

  /** Stable: the advisory lock key and the cursor row are derived from it. */
  static final String JOB_NAME = "insights-bills-daily";

  /** Before every id: the database orders uuids as unsigned bytes, so all zeros comes first. */
  private static final UUID BEFORE_ALL = new UUID(0L, 0L);

  private final JobLock jobLock;
  private final BiUserBillRepository bills;
  private final BiJobCursorRepository cursors;
  private final BillService service;
  private final InsightsProperties properties;
  private final TransactionTemplate tx;

  @Scheduled(
      cron = "${moneyreserve.insights.jobs.bills-cron:0 15/30 7-10 * * *}",
      zone = "${moneyreserve.insights.zone:Asia/Colombo}")
  public void run() {
    jobLock.runExclusively(JOB_NAME, this::runNow);
  }

  /** One run: continues today's pass where it stopped, or starts it. Returns customers checked. */
  int runNow() {
    if (!properties.isEnabled() || !properties.getBills().isEnabled()) {
      return 0;
    }
    InsightsProperties.Bills config = properties.getBills();
    LocalDate today = LocalDate.now(properties.zoneId());
    BiJobCursor cursor =
        cursors
            .findById(JOB_NAME)
            .filter(c -> today.equals(c.getRunDate()))
            .orElseGet(() -> BiJobCursor.builder().jobName(JOB_NAME).runDate(today).completed(false).build());
    if (cursor.isCompleted()) {
      return 0;
    }
    long started = System.nanoTime();
    Duration limit = config.getDailyJobTimeLimit();
    LocalDate dueBy = today.plusDays(config.getDailyJobDueWithinDays());
    UUID after = cursor.getLastKey() == null ? BEFORE_ALL : UUID.fromString(cursor.getLastKey());
    int done = 0;
    int failed = 0;
    boolean finished = false;
    while (true) {
      List<UUID> page =
          bills.findDueUserIdsAfter(dueBy, after, PageRequest.of(0, Math.max(1, config.getDailyJobPageSize())));
      if (page.isEmpty()) {
        finished = true;
        break;
      }
      for (UUID userId : page) {
        try {
          tx.executeWithoutResult(status -> service.refresh(userId, today));
          done++;
        } catch (RuntimeException ex) {
          failed++;
          log.error("Insights bills daily check skipped one customer", ex);
        }
        after = userId;
      }
      save(cursor, after, false);
      if (Duration.ofNanos(System.nanoTime() - started).compareTo(limit) >= 0) {
        break;
      }
    }
    if (finished) {
      save(cursor, after == BEFORE_ALL ? null : after, true);
    }
    log.info(
        "Insights bills daily check {} checked={} failed={} took={}ms",
        finished ? "completed" : "paused (time limit), continues on the next run",
        done,
        failed,
        Duration.ofNanos(System.nanoTime() - started).toMillis());
    return done;
  }

  private void save(BiJobCursor cursor, UUID last, boolean completed) {
    cursor.setLastKey(last == null || last.equals(BEFORE_ALL) ? null : last.toString());
    cursor.setCompleted(completed);
    cursor.setUpdatedAt(OffsetDateTime.now());
    tx.executeWithoutResult(status -> cursors.save(cursor));
  }
}
