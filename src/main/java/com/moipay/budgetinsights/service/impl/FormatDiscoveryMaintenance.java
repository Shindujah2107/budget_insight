package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiSmsFormatCandidate;
import com.moipay.budgetinsights.domain.FormatCandidateStatus;
import com.moipay.budgetinsights.repository.BiConsentRepository;
import com.moipay.budgetinsights.repository.BiSmsFormatCandidateRepository;
import com.moipay.budgetinsights.repository.BiSmsFormatSampleRepository;
import com.moipay.budgetinsights.repository.BiUnmatchedSmsStatRepository;
import com.moipay.budgetinsights.service.FormatDiscoveryService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The daily housekeeping for SMS format discovery, run by {@code InsightsRetentionJob}.
 *
 * <p>Runs whatever the release flags say, like the rest of retention: data already collected must
 * keep expiring after a feature is switched off, or switching it off would be the way to keep it.
 *
 * <ol>
 *   <li>Customers who withdrew consent in the last few days lose any sample that raced in.
 *   <li>Samples past their 90 days are deleted, and every format is recounted — a format can fall
 *       back below the floor and disappear from the admin list.
 *   <li>Formats with no sample left and not seen for that long are deleted outright.
 *   <li>Per-device daily counts past their retention are deleted.
 *   <li>Open formats that a live template now reads are marked RESOLVED.
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FormatDiscoveryMaintenance {

  private final InsightsProperties properties;
  private final BiSmsFormatSampleRepository samples;
  private final BiSmsFormatCandidateRepository candidates;
  private final BiUnmatchedSmsStatRepository stats;
  private final FormatCoverage coverage;
  private final BiConsentRepository consents;
  private final FormatDiscoveryService discovery;

  /** How far back the pass looks for withdrawals whose samples may have raced in. */
  static final int WITHDRAWAL_LOOKBACK_DAYS = 3;

  /** What one pass did, for the job's log line and for tests. */
  public record Outcome(
      int samplesExpired, int formatsRemoved, int countsRemoved, int formatsResolved, int samplesForgotten) {}

  /**
   * One pass, in its own transaction. REQUIRES_NEW because the retention job calls this inside
   * the advisory-lock transaction on PostgreSQL: joined, a failure here would mark that
   * transaction rollback-only and undo the raw-message and export purges done before it.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome run(OffsetDateTime now) {
    int ttlDays = Math.max(1, properties.getFormatDiscovery().getSampleTtlDays());
    int statDays = Math.max(1, properties.getFormatDiscovery().getStatRetentionDays());

    // An upload that passed its consent check just before a withdrawal can commit just after the
    // withdrawal removed that customer's samples. Sweeping recent withdrawals closes that gap.
    int forgotten = 0;
    for (UUID user : consents.findUserIdsWithdrawnSince(now.minusDays(WITHDRAWAL_LOOKBACK_DAYS))) {
      boolean stillWithdrawn =
          consents.findFirstByUserIdOrderByCreatedAtDesc(user).map(c -> !c.isActive()).orElse(false);
      if (stillWithdrawn) {
        forgotten += discovery.forget(user);
      }
    }

    int expired = samples.deleteExpired(now);

    List<UUID> abandoned = candidates.findAbandoned(now.minusDays(ttlDays));
    int removed = 0;
    if (!abandoned.isEmpty()) {
      samples.deleteByCandidateIdIn(abandoned);
      removed = candidates.deleteByIds(abandoned);
    }

    LocalDate today = now.atZoneSameInstant(properties.zoneId()).toLocalDate();
    int counts = stats.deleteOlderThan(today.minusDays(statDays));

    // Every format's customer count from its live samples, whatever happened today.
    candidates.recountAll();

    int resolved = resolveCovered(now);

    return new Outcome(expired, removed, counts, resolved, forgotten);
  }

  /** The same test the admin list shows as "covered", recorded so it stops being re-asked. */
  private int resolveCovered(OffsetDateTime now) {
    List<BiSmsFormatCandidate> open = candidates.findByStatus(FormatCandidateStatus.OPEN);
    if (open.isEmpty()) {
      return 0;
    }
    FormatCoverage.Checker checker = coverage.checker();
    int resolved = 0;
    for (BiSmsFormatCandidate c : open) {
      UUID template = checker.templateReading(c);
      if (template != null) {
        c.setStatus(FormatCandidateStatus.RESOLVED);
        c.setTemplateId(template);
        c.setDecidedAt(now);
        candidates.save(c);
        resolved++;
      }
    }
    return resolved;
  }
}
