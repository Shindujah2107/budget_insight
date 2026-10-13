package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiBankParserTemplate;
import com.moipay.budgetinsights.domain.BiSmsFormatCandidate;
import com.moipay.budgetinsights.domain.FormatCandidateStatus;
import com.moipay.budgetinsights.dto.AdminFormatCandidate;
import com.moipay.budgetinsights.dto.AdminFormatCandidateDraft;
import com.moipay.budgetinsights.dto.AdminPage;
import com.moipay.budgetinsights.dto.AdminSenderCoverage;
import com.moipay.budgetinsights.dto.AdminSmsVocabulary;
import com.moipay.budgetinsights.dto.AdminTemplateAnalyseRequest;
import com.moipay.budgetinsights.dto.AdminTemplateAnalysis;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiBankParserTemplateRepository;
import com.moipay.budgetinsights.repository.BiSmsFormatCandidateRepository;
import com.moipay.budgetinsights.repository.BiUnmatchedSmsStatRepository;
import com.moipay.budgetinsights.service.AdminConfigService;
import com.moipay.budgetinsights.service.AdminFormatCandidateService;
import com.moipay.budgetinsights.service.AdminTemplateService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMS format discovery for the Super Admin: which banks need templates, which message formats
 * real customers receive that nothing reads, and a head start on the template for one.
 *
 * <p><b>The k-anonymity floor is enforced in the query, not here and not in the UI.</b> A format
 * fewer than {@code effectiveMinDistinctUsers()} people sent does not exist as far as any admin
 * route is concerned: not in the list, not by id, not as a draft. Bank wording repeats across
 * customers; anything particular to one customer does not, and that is the whole safeguard for a
 * format that slipped something odd past the grammar.
 *
 * <p><b>"Covered" is computed from the live templates.</b> A format is shown as covered when a
 * published template reads the sample built from it ({@link FormatCoverage}) — the same test the
 * phones will apply when they re-read their queues. {@link FormatDiscoveryMaintenance} records
 * that as RESOLVED daily.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminFormatCandidateServiceImpl implements AdminFormatCandidateService {

  static final int MAX_PAGE_SIZE = 100;

  private final InsightsProperties properties;
  private final BiUnmatchedSmsStatRepository stats;
  private final BiSmsFormatCandidateRepository candidates;
  private final BiBankParserTemplateRepository templates;
  private final AdminConfigService config;
  private final AdminTemplateService templateTools;
  private final SmsVocabulary vocabulary;
  private final FormatCoverage coverage;

  // ---------------------------------------------------------------------------------
  // Phase 1
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public List<AdminSenderCoverage> senderCoverage(int days) {
    LocalDate from = LocalDate.now(properties.zoneId()).minusDays(days - 1L);
    Map<String, String> bankBySender = coverage.bankBySender();
    Map<String, Integer> liveByBank = new HashMap<>();
    for (BiBankParserTemplate t : templates.findAllByIsActiveTrue()) {
      liveByBank.merge(t.getBankCode(), 1, Integer::sum);
    }
    int k = properties.getFormatDiscovery().effectiveMinDistinctUsers();
    List<AdminSenderCoverage> out = new ArrayList<>();
    for (BiUnmatchedSmsStatRepository.SenderTotals row : stats.totalsSince(from)) {
      String bank = bankBySender.get(row.getSenderId().toUpperCase(Locale.ROOT));
      long devices = row.getDevices() == null ? 0 : row.getDevices();
      // A sender we do not know is shown only once enough devices saw it: a name only one or two
      // phones received is more likely a person than a bank, and is nobody else's business.
      if (bank == null && devices < k) {
        continue;
      }
      out.add(
          new AdminSenderCoverage(
              row.getSenderId(),
              bank,
              config.bankName(bank),
              row.getMessages() == null ? 0 : row.getMessages(),
              devices,
              row.getLastDay(),
              bank != null,
              bank == null ? 0 : liveByBank.getOrDefault(bank, 0)));
    }
    out.sort(
        Comparator.comparingLong(AdminSenderCoverage::unmatched)
            .reversed()
            .thenComparing(AdminSenderCoverage::senderId));
    log.info("bi-admin sender coverage days={} senders={}", days, out.size());
    return out;
  }

  // ---------------------------------------------------------------------------------
  // Phase 2
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminPage<AdminFormatCandidate> list(
      FormatCandidateStatus status, String senderId, int page, int size) {
    int k = properties.getFormatDiscovery().effectiveMinDistinctUsers();
    PageRequest request = PageRequest.of(page, Math.max(1, Math.min(MAX_PAGE_SIZE, size)));
    Page<BiSmsFormatCandidate> found =
        senderId == null || senderId.isBlank()
            ? candidates.findVisible(status, k, request)
            : candidates.findVisibleBySender(
                status, k, senderId.trim().toUpperCase(Locale.ROOT), request);
    FormatCoverage.Checker checker = coverage.checker();
    List<AdminFormatCandidate> items =
        found.getContent().stream().map(c -> toItem(c, checker)).toList();
    log.info(
        "bi-admin format candidates status={} sender={} page={} returned={} total={}",
        status,
        senderId == null ? "any" : senderId,
        page,
        items.size(),
        found.getTotalElements());
    return new AdminPage<>(items, page, request.getPageSize(), found.getTotalElements(), found.getTotalPages());
  }

  @Override
  @Transactional(readOnly = true)
  public AdminFormatCandidate get(UUID id) {
    return toItem(visible(id), coverage.checker());
  }

  // ---------------------------------------------------------------------------------
  // Phase 3
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminFormatCandidateDraft draftTemplate(UUID id, String bankCode) {
    BiSmsFormatCandidate c = visible(id);
    if (c.getStatus() != FormatCandidateStatus.OPEN) {
      throw notOpen();
    }
    String sample = coverage.checker().sampleOf(c);
    // The wizard's own analysis, exactly as if an admin had pasted this sample: same bank check,
    // same OTP refusal, same suggestions. From here the admin continues in the existing wizard.
    AdminTemplateAnalysis analysis =
        templateTools.analyse(new AdminTemplateAnalyseRequest(bankCode, sample));
    log.info(
        "bi-admin format candidate drafted id={} bank={} suggestions={}",
        id,
        bankCode,
        analysis.suggestions().size());
    return new AdminFormatCandidateDraft(c.getId(), bankCode, sample, analysis);
  }

  @Override
  @Transactional
  public AdminFormatCandidate reject(UUID id, String reason, UUID adminUserId) {
    BiSmsFormatCandidate c = visible(id);
    if (c.getStatus() != FormatCandidateStatus.OPEN) {
      throw notOpen();
    }
    c.setStatus(FormatCandidateStatus.REJECTED);
    c.setRejectReason(reason);
    c.setDecidedBy(adminUserId);
    c.setDecidedAt(OffsetDateTime.now(properties.zoneId()));
    candidates.save(c);
    log.info("bi-admin format candidate rejected id={} reason={} by={}", id, reason, adminUserId);
    return toItem(c, coverage.checker());
  }

  @Override
  @Transactional(readOnly = true)
  public AdminSmsVocabulary vocabulary() {
    List<AdminSmsVocabulary.Word> words =
        vocabulary.rows().stream()
            .map(
                w ->
                    new AdminSmsVocabulary.Word(
                        w.getId(),
                        w.getWord(),
                        w.getSource(),
                        Boolean.TRUE.equals(w.getIsActive()),
                        w.getAddedInVersion()))
            .toList();
    return new AdminSmsVocabulary(vocabulary.currentVersion(), words);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /** The candidate if an admin may see it at all; otherwise the same 404 as an unknown id. */
  private BiSmsFormatCandidate visible(UUID id) {
    int k = properties.getFormatDiscovery().effectiveMinDistinctUsers();
    return candidates
        .findById(id)
        .filter(c -> c.getDistinctUsers() != null && c.getDistinctUsers() >= k)
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND));
  }

  private static BudgetInsightsException notOpen() {
    return new BudgetInsightsException(
        ErrorCode.ADMIN_FIELD_INVALID, "Only a format that is still open can be changed.");
  }

  private AdminFormatCandidate toItem(BiSmsFormatCandidate c, FormatCoverage.Checker checker) {
    String bank = checker.bankOf(c.getSenderId());
    UUID coveredBy = c.getStatus() == FormatCandidateStatus.OPEN ? checker.templateReading(c) : null;
    return new AdminFormatCandidate(
        c.getId(),
        c.getSenderId(),
        bank,
        config.bankName(bank),
        c.getDisplayText(),
        c.getDistinctUsers(),
        c.getSampleCount(),
        c.getFirstSeenAt(),
        c.getLastSeenAt(),
        c.getStatus().name(),
        c.getRejectReason(),
        c.getTemplateId(),
        coveredBy);
  }
}
