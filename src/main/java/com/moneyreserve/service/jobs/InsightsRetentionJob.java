package com.moneyreserve.service.jobs;

import com.moneyreserve.repository.BiDataExportRepository;
import com.moneyreserve.repository.BiRawMessageRepository;
import com.moneyreserve.service.impl.FormatDiscoveryMaintenance;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BI-30 — the TTL purge, and the first job this service has ever run.
 *
 * <p>Two tables expire on a clock, and they expire for the same reason: both hold something the
 * service is only entitled to keep for a while. {@code bi_raw_message} holds encrypted message text
 * captured on the consent-gated parser-improvement path; {@code bi_data_export} holds a second copy
 * of a customer's whole history waiting to be downloaded. One job clears both, under one lock,
 * because they are the same bounded delete on the same schedule and two locks would be two things
 * to reason about at three in the morning.
 *
 * <p><b>It runs whether or not the feature is switched on.</b> The master flag decides whether
 * customers can see Budget Insights; it does not decide whether data already captured may go on
 * being kept. A retention obligation that paused when the UI was turned off would be a retention
 * obligation in name only. The job is disabled only by {@code jobs.enabled=false}, which exists for
 * local development, not for production.
 *
 * <p>The lock is what makes this safe with {@code desired_count = 2}. Without it both tasks fire at
 * the same instant and race over the same rows — which for a delete is survivable and for the
 * aggregate refresh beside it is not, so the same control covers both.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "moneyreserve.insights.jobs",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InsightsRetentionJob {

  /** Stable: the advisory lock key is derived from it, so renaming it releases the old lock. */
  static final String JOB_NAME = "insights-retention-purge";

  private final JobLock jobLock;
  private final BiRawMessageRepository rawMessages;
  private final BiDataExportRepository exports;
  private final FormatDiscoveryMaintenance formatDiscovery;

  @Scheduled(
      cron = "${moneyreserve.insights.jobs.retention-cron:0 15 2 * * *}",
      zone = "${moneyreserve.insights.zone:Asia/Colombo}")
  public void purgeExpired() {
    jobLock.runExclusively(JOB_NAME, this::purgeNow);
  }

  /**
   * SMS format discovery's share of the purge, isolated: a failure there is logged and retried
   * tomorrow, and never stops the raw-message and export purges, which predate it. Isolation
   * needs both halves: this catch, and {@link FormatDiscoveryMaintenance#run} running in its own
   * transaction (REQUIRES_NEW) — on PostgreSQL the job lock holds an outer transaction, and a
   * failure that joined it would roll the existing purges back with it.
   */
  private void purgeFormatDiscovery(OffsetDateTime now) {
    try {
      FormatDiscoveryMaintenance.Outcome formats = formatDiscovery.run(now);
      log.info(
          "Insights retention: SMS format discovery samplesExpired={} formatsRemoved={}"
              + " countsRemoved={} formatsResolved={} samplesForgotten={}",
          formats.samplesExpired(),
          formats.formatsRemoved(),
          formats.countsRemoved(),
          formats.formatsResolved(),
          formats.samplesForgotten());
    } catch (RuntimeException ex) {
      log.error("Insights retention: SMS format discovery maintenance failed; will retry next run", ex);
    }
  }

  /**
   * Package-private so a test can run the body without waiting for a cron or taking a lock.
   *
   * <p>The scheduled method above is one line for exactly this reason: what is worth testing is
   * what gets deleted, and a test that had to go through the scheduler to reach it would be a test
   * of Spring.
   */
  void purgeNow() {
    OffsetDateTime now = OffsetDateTime.now();

    int messages = rawMessages.deleteExpired(now);
    int documents = exports.deleteExpired(now);
    purgeFormatDiscovery(now);

    if (messages > 0 || documents > 0) {
      log.info(
          "Insights retention purge removed {} raw messages and {} expired exports",
          messages,
          documents);
    } else {
      log.info("Insights retention purge found nothing expired");
    }
  }
}
