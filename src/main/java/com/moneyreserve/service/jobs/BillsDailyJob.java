package com.moneyreserve.service.jobs;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.BillService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bills radar, once a day: re-checks every customer who has a bill so "due soon" and "missed?"
 * alerts arrive even on days they record no payment. One customer's failure does not stop the
 * rest. Runs on one task only ({@link JobLock}).
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

  /** Stable: the advisory lock key is derived from it. */
  static final String JOB_NAME = "insights-bills-daily";

  private final JobLock jobLock;
  private final BiUserBillRepository bills;
  private final BillService service;
  private final InsightsProperties properties;
  private final TransactionTemplate tx;

  @Scheduled(
      cron = "${moneyreserve.insights.jobs.bills-cron:0 15 7 * * *}",
      zone = "${moneyreserve.insights.zone:Asia/Colombo}")
  public void run() {
    jobLock.runExclusively(JOB_NAME, this::runNow);
  }

  int runNow() {
    if (!properties.isEnabled() || !properties.getBills().isEnabled()) {
      return 0;
    }
    LocalDate today = LocalDate.now(properties.zoneId());
    List<UUID> users = bills.findLiveUserIds();
    int done = 0;
    for (UUID userId : users) {
      try {
        tx.executeWithoutResult(status -> service.refresh(userId, today));
        done++;
      } catch (RuntimeException ex) {
        log.error("Insights bills daily check skipped one customer", ex);
      }
    }
    log.info("Insights bills daily check covered {} of {} customers", done, users.size());
    return done;
  }
}
