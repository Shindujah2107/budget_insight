package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiSmsFormatCandidate;
import com.moipay.budgetinsights.domain.BiSmsFormatSample;
import com.moipay.budgetinsights.domain.BiUnmatchedSmsStat;
import com.moipay.budgetinsights.domain.FormatCandidateStatus;
import com.moipay.budgetinsights.dto.FormatSample;
import com.moipay.budgetinsights.dto.FormatSampleBatchRequest;
import com.moipay.budgetinsights.dto.FormatSampleBatchResponse;
import com.moipay.budgetinsights.dto.FormatSampleResultItem;
import com.moipay.budgetinsights.dto.UnmatchedSmsStat;
import com.moipay.budgetinsights.dto.UnmatchedStatsRequest;
import com.moipay.budgetinsights.dto.UnmatchedStatsResponse;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiSmsFormatCandidateRepository;
import com.moipay.budgetinsights.repository.BiSmsFormatSampleRepository;
import com.moipay.budgetinsights.repository.BiUnmatchedSmsStatRepository;
import com.moipay.budgetinsights.service.ConsentService;
import com.moipay.budgetinsights.service.FormatDiscoveryService;
import com.moipay.budgetinsights.service.ingest.IngestRateLimiter;
import com.moipay.budgetinsights.service.ingest.OtpGuard;
import com.moipay.budgetinsights.service.ingest.SkeletonGrammar;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMS format discovery, the handset side (Phases 1 and 2).
 *
 * <p>Shaped like {@link ClientComplianceServiceImpl} on purpose — a device queues, sends a batch,
 * reads one result per item and clears exactly what was answered — so a handset has one retry
 * model for every Insights upload, not three.
 *
 * <p><b>What arrives here is never a message.</b> Phase 1 is a number per sender per day. Phase 2
 * is a skeleton: vocabulary ids and typed placeholders, every token checked by {@link
 * SkeletonGrammar} before anything is written. Nothing in either request can hold an amount, an
 * account number, a name or an OTP, and nothing in the stored rows can either.
 *
 * <p><b>Who sent it is not kept.</b> Counts carry a device hash (so a retry replaces rather than
 * adds); samples carry an HMAC of the user (so five different people can be counted and one
 * person's rows can be found again on withdrawal). Neither stores a user id.
 */
@Slf4j
@Service
public class FormatDiscoveryServiceImpl implements FormatDiscoveryService {

  static final String ACCEPTED = "ACCEPTED";
  static final String DUPLICATE = "DUPLICATE";
  static final String REJECTED = "REJECTED";

  /** How far back a daily count may be. The phone uploads daily; a week covers a phone left off. */
  static final int STAT_WINDOW_DAYS = 7;

  static final int MAX_COUNT_PER_DAY = 500;

  private static final Pattern SENDER = Pattern.compile("^[A-Za-z0-9 _+.-]{2,16}$");

  /**
   * At most this many digits in a sender. Bank alerts come from alphanumeric sender ids or short
   * codes; more digits than a short code is somebody's phone number (in any punctuation), or a
   * code, and recording it would put a person or a secret into a table.
   */
  static final int MAX_SENDER_DIGITS = 5;

  private static final Pattern CLIENT_REF = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

  private final InsightsProperties properties;
  private final ConsentService consent;
  private final DeviceIdHasher deviceHasher;
  private final UserHasher userHasher;
  private final SmsVocabulary vocabulary;
  private final BiUnmatchedSmsStatRepository stats;
  private final BiSmsFormatCandidateRepository candidates;
  private final BiSmsFormatSampleRepository samples;

  /** Its own limiter, constructed here for the reason {@link ClientComplianceServiceImpl} gives. */
  private final IngestRateLimiter sampleLimiter;

  public FormatDiscoveryServiceImpl(
      InsightsProperties properties,
      ConsentService consent,
      DeviceIdHasher deviceHasher,
      UserHasher userHasher,
      SmsVocabulary vocabulary,
      BiUnmatchedSmsStatRepository stats,
      BiSmsFormatCandidateRepository candidates,
      BiSmsFormatSampleRepository samples) {
    this.properties = properties;
    this.consent = consent;
    this.deviceHasher = deviceHasher;
    this.userHasher = userHasher;
    this.vocabulary = vocabulary;
    this.stats = stats;
    this.candidates = candidates;
    this.samples = samples;
    this.sampleLimiter =
        new IngestRateLimiter(
            Math.max(1, properties.getFormatDiscovery().getMaxSamplesPerUserPerDay()),
            Duration.ofDays(1));
  }

  // ---------------------------------------------------------------------------------
  // Phase 1: counts
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public UnmatchedStatsResponse recordStats(Caller caller, UnmatchedStatsRequest request) {
    consent.requireActiveConsent(caller.userId());
    String device = deviceHasher.hashDeviceId(request.getDeviceId());
    LocalDate today = LocalDate.now(properties.zoneId());
    LocalDate earliest = today.minusDays(STAT_WINDOW_DAYS);

    // Valid items, merged by day and sender. A device that sent the same day twice in one batch
    // gets the larger count, not the sum: each item is a full day's count, not an increment.
    Map<String, UnmatchedSmsStat> valid = new LinkedHashMap<>();
    int rejected = 0;
    for (UnmatchedSmsStat s : request.getStats()) {
      if (s == null
          || s.getSenderId() == null
          || !acceptableSender(s.getSenderId())
          || s.getDay() == null
          || s.getDay().isBefore(earliest)
          || s.getDay().isAfter(today)
          || s.getCount() == null
          || s.getCount() < 1
          || s.getCount() > MAX_COUNT_PER_DAY) {
        rejected++;
        continue;
      }
      String sender = s.getSenderId().toUpperCase(Locale.ROOT);
      valid.merge(
          s.getDay() + "|" + sender,
          new UnmatchedSmsStat(sender, s.getDay(), s.getCount()),
          (a, b) -> a.getCount() >= b.getCount() ? a : b);
    }

    if (!valid.isEmpty()) {
      Set<LocalDate> days = new HashSet<>();
      valid.values().forEach(s -> days.add(s.getDay()));
      Map<String, BiUnmatchedSmsStat> existing = new HashMap<>();
      for (BiUnmatchedSmsStat row : stats.findByDeviceHashAndStatDayIn(device, days)) {
        existing.put(row.getStatDay() + "|" + row.getSenderId(), row);
      }
      List<BiUnmatchedSmsStat> toSave = new ArrayList<>(valid.size());
      valid.forEach(
          (key, s) -> {
            BiUnmatchedSmsStat row = existing.get(key);
            if (row == null) {
              row =
                  BiUnmatchedSmsStat.builder()
                      .statDay(s.getDay())
                      .senderId(s.getSenderId())
                      .deviceHash(device)
                      .build();
            }
            // Replace, never add: the phone sends a finished day's total, and a retry of the same
            // upload must leave the figure where it was.
            row.setMsgCount(s.getCount());
            toSave.add(row);
          });
      stats.saveAll(toSave);
    }

    log.info(
        "Insights unmatched-SMS counts userId={} received={} recorded={} rejected={}",
        caller.userId(),
        request.getStats().size(),
        valid.size(),
        rejected);
    return UnmatchedStatsResponse.builder()
        .recorded(request.getStats().size() - rejected)
        .rejected(rejected)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Phase 2: skeletons
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public FormatSampleBatchResponse recordSamples(Caller caller, FormatSampleBatchRequest request) {
    List<FormatSample> batch = request.getSamples();
    requireWithinRateLimit(caller, batch.size());
    consent.requireActiveConsent(caller.userId());

    String userHash = userHasher.hash(caller.userId());
    int phoneVersion = request.getVocabularyVersion();
    // A version the server never issued means the phone's vocabulary is not ours: nothing it sent
    // can be read with confidence, so every sample is refused rather than guessed at.
    boolean knownVersion = phoneVersion <= vocabulary.currentVersion();
    Map<Integer, String> words = knownVersion ? vocabulary.usableAt(phoneVersion) : Map.of();

    OffsetDateTime now = OffsetDateTime.now(properties.zoneId());
    OffsetDateTime expires =
        now.plusDays(Math.max(1, properties.getFormatDiscovery().getSampleTtlDays()));

    List<FormatSampleResultItem> results = new ArrayList<>(batch.size());
    Map<String, BiSmsFormatCandidate> touched = new LinkedHashMap<>();
    Set<String> seenInBatch = new HashSet<>();
    Map<UUID, Integer> acceptedPerCandidate = new HashMap<>();
    int accepted = 0;
    int duplicates = 0;
    int rejected = 0;

    for (FormatSample sample : batch) {
      String clientRef = sample == null ? null : sample.getClientRef();
      if (sample == null || clientRef == null || !CLIENT_REF.matcher(clientRef).matches()) {
        // No usable reference: the device could not match a result to this row anyway.
        results.add(reject(null, ErrorCode.INSIGHTS_FORMAT_ITEM_INVALID));
        rejected++;
        continue;
      }
      String sender = sample.getSenderId();
      if (!knownVersion
          || sender == null
          || !acceptableSender(sender)
          || sample.getTokens() == null) {
        results.add(reject(clientRef, ErrorCode.INSIGHTS_FORMAT_ITEM_INVALID));
        rejected++;
        continue;
      }
      sender = sender.toUpperCase(Locale.ROOT);

      SkeletonGrammar.Result read = SkeletonGrammar.read(sample.getTokens(), words);
      if (!read.ok()) {
        results.add(
            reject(
                clientRef,
                read.problem() == SkeletonGrammar.Problem.NOT_USEFUL
                    ? ErrorCode.INSIGHTS_FORMAT_ITEM_NOT_USEFUL
                    : ErrorCode.INSIGHTS_FORMAT_ITEM_INVALID));
        rejected++;
        continue;
      }

      SkeletonGrammar.Skeleton skeleton = read.skeleton();
      String hash = SkeletonGrammar.formatHash(sender, skeleton.key());
      if (!seenInBatch.add(hash)) {
        results.add(duplicate(clientRef));
        duplicates++;
        continue;
      }

      BiSmsFormatCandidate candidate = touched.get(hash);
      if (candidate == null) {
        candidate =
            candidates
                .findBySenderIdAndFormatHash(sender, hash)
                .orElseGet(() -> BiSmsFormatCandidate.builder().build());
        if (candidate.getFormatHash() == null) {
          candidate.setSenderId(sender);
          candidate.setFormatHash(hash);
          candidate.setTokenKey(skeleton.key());
          candidate.setDisplayText(skeleton.display());
          candidate.setTokenCount(skeleton.tokens().size());
          candidate.setFirstSeenAt(now);
          candidate.setLastSeenAt(now);
          candidate.setStatus(FormatCandidateStatus.OPEN);
          candidate.setDistinctUsers(0);
          candidate.setSampleCount(0);
          candidate = candidates.save(candidate);
        }
        touched.put(hash, candidate);
      }
      candidate.setLastSeenAt(now);

      // One vote per user per format: a customer who keeps receiving the same unreadable SMS is
      // one person, and counting them again would let one phone push a format over the floor.
      if (samples.existsByCandidateIdAndUserHash(candidate.getId(), userHash)) {
        results.add(duplicate(clientRef));
        duplicates++;
        continue;
      }
      samples.save(
          BiSmsFormatSample.builder()
              .candidateId(candidate.getId())
              .userHash(userHash)
              .vocabularyVersion(phoneVersion)
              .platform(request.getPlatform())
              .receivedAt(now)
              .ttlExpiresAt(expires)
              .build());
      acceptedPerCandidate.merge(candidate.getId(), 1, Integer::sum);
      results.add(FormatSampleResultItem.builder().clientRef(clientRef).status(ACCEPTED).build());
      accepted++;
    }

    // Not caught, for the reason ClientComplianceServiceImpl gives: answering ACCEPTED for a row
    // that was never written is how a report disappears. Two phones racing on a brand-new format
    // can collide on the candidate's unique key; that fails this batch, and the retry finds it.
    candidates.saveAll(touched.values());
    // distinct_users is recounted from the samples, never incremented: an increment on a row read
    // earlier in this transaction would overwrite a concurrent expiry or withdrawal and could hold
    // a format above the floor with fewer live customers than the floor requires.
    if (!touched.isEmpty()) {
      candidates.flush();
      acceptedPerCandidate.forEach(candidates::addSamples);
      recount(touched.values().stream().map(BiSmsFormatCandidate::getId).toList());
    }
    // 3: consent checked again at the end. A withdrawal or erasure that committed while this
    // request ran turns it into a rollback here rather than a sample that outlives the consent.
    // (A withdrawal that lands after this point is swept by the daily pass.)
    consent.requireActiveConsent(caller.userId());

    // Counts only. Never the tokens, the rendered shape or a hash: the shape of a message plus a
    // user id in one log line is more than this feature promises to keep.
    log.info(
        "Insights format samples userId={} received={} accepted={} duplicates={} rejected={}",
        caller.userId(),
        batch.size(),
        accepted,
        duplicates,
        rejected);
    return FormatSampleBatchResponse.builder()
        .accepted(accepted)
        .duplicates(duplicates)
        .rejected(rejected)
        .results(results)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Withdrawal and erasure
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public int forget(UUID userId) {
    String userHash = userHasher.hash(userId);
    if (userHash == null) {
      // No key means no sample could ever have been written for anybody.
      return 0;
    }
    List<UUID> affected = samples.findCandidateIdsByUserHash(userHash);
    int removed = samples.deleteByUserHash(userHash);
    recount(affected);
    if (removed > 0) {
      log.info("Insights format samples removed userId={} samples={}", userId, removed);
    }
    return removed;
  }

  /** distinct_users follows the live samples, so a format can drop back below the floor. */
  void recount(Collection<UUID> candidateIds) {
    if (!candidateIds.isEmpty()) {
      candidates.recount(candidateIds);
    }
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /** A bank-like sender: the agreed characters, few digits, and no OTP / PIN wording. */
  static boolean acceptableSender(String sender) {
    if (sender == null || !SENDER.matcher(sender).matches()) {
      return false;
    }
    long digits = sender.chars().filter(Character::isDigit).count();
    return digits <= MAX_SENDER_DIGITS && !OtpGuard.trips(sender);
  }

  private static FormatSampleResultItem reject(String clientRef, ErrorCode code) {
    return FormatSampleResultItem.builder()
        .clientRef(clientRef)
        .status(REJECTED)
        .error(code.getCode())
        .build();
  }

  private static FormatSampleResultItem duplicate(String clientRef) {
    return FormatSampleResultItem.builder().clientRef(clientRef).status(DUPLICATE).build();
  }

  private void requireWithinRateLimit(Caller caller, int count) {
    Instant now = Instant.now();
    sampleLimiter.evictExpired(now);
    if (sampleLimiter.tryConsume(caller.userId(), count, now)) {
      return;
    }
    long retryAfter = sampleLimiter.retryAfterSeconds(caller.userId(), now);
    log.info(
        "Insights format samples throttled userId={} batchSize={} retryAfterSeconds={}",
        caller.userId(),
        count,
        retryAfter);
    throw new BudgetInsightsException(ErrorCode.INSIGHTS_FORMAT_RATE_LIMITED, null, retryAfter);
  }
}
