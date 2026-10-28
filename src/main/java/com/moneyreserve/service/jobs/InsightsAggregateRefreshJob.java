package com.moneyreserve.service.jobs;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.InsightsQueryService;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BI-29 — the daily aggregate refresh.
 *
 * <p>Read-through already fills the cache for anyone who opens the screen. What this adds is the
 * first read of the day being fast rather than the one that pays for everyone else, and a cache
 * that stays warm across a period boundary: at one minute past midnight on the first of the month
 * every customer's cached period is yesterday's, and without this the whole population would
 * recompute on their next visit.
 *
 * <p><b>Active customers only.</b> "Everyone who has ever transacted" grows without bound and most
 * of it is dormant; the look-back window is already the compliance position on how much history is
 * analysed, so it is the honest definition of who this service is currently doing anything for.
 *
 * <p>One customer's failure does not stop the rest. A refresh is an optimisation, and a job that
 * abandoned nine thousand customers because the ten-thousandth had an unreadable row would turn one
 * bad row into a service-wide loss of the cache.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "moneyreserve.insights.jobs",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InsightsAggregateRefreshJob {

  /** Stable: the advisory lock key is derived from it. */
  static final String JOB_NAME = "insights-aggregate-refresh";

  private final JobLock jobLock;
  private final BiTransactionRepository transactions;
  private final InsightsQueryService queries;
  private final InsightsProperties properties;

  @Scheduled(
      cron = "${moneyreserve.insights.jobs.refresh-cron:0 45 2 * * *}",
      zone = "${moneyreserve.insights.zone:Asia/Colombo}")
  public void refresh() {
    jobLock.runExclusively(JOB_NAME, this::refreshNow);
  }

  /** Package-private for the same reason as the retention job: the work is what is tested. */
  int refreshNow() {
    LocalDate today = LocalDate.now(properties.zoneId());
    LocalDate since = today.minusMonths(Math.max(1, properties.getLookbackMonths()));
    InsightsPeriod period = InsightsPeriod.ofMonth(YearMonth.from(today));

    List<UUID> active = transactions.findActiveUserIdsSince(since);
    int refreshed = 0;
    for (UUID userId : active) {
      try {
        // Goes through the read path rather than round the side of it, so the cached payload is
        // the same object the endpoint would have returned. A refresh that computed the figures
        // its own way would be a second implementation of the breakdown, quietly diverging.
        queries.breakdown(userId, period);
        refreshed++;
      } catch (RuntimeException ex) {
        log.error("Insights aggregate refresh skipped one customer", ex);
      }
    }

    log.info(
        "Insights aggregate refresh covered {} of {} active customers for {}",
        refreshed,
        active.size(),
        period.start());
    return refreshed;
  }
}
