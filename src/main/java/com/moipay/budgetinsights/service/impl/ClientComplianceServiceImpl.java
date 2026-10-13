package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiComplianceEvent;
import com.moipay.budgetinsights.domain.ComplianceEventAction;
import com.moipay.budgetinsights.domain.ComplianceEventSource;
import com.moipay.budgetinsights.domain.ComplianceEventType;
import com.moipay.budgetinsights.dto.ClientComplianceEvent;
import com.moipay.budgetinsights.dto.ComplianceEventBatchRequest;
import com.moipay.budgetinsights.dto.ComplianceEventBatchResponse;
import com.moipay.budgetinsights.dto.ComplianceEventResultItem;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiComplianceEventRepository;
import com.moipay.budgetinsights.service.ClientComplianceService;
import com.moipay.budgetinsights.service.ConsentService;
import com.moipay.budgetinsights.service.ingest.IngestRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records OTP and PIN detections reported by a handset (BI-36).
 *
 * <p>The shape of this class is the ingest pipeline's, deliberately, because a device treats the
 * two routes the same way: it queues, it sends a batch, it reads a per-item result set, it clears
 * exactly what landed. Anything that behaved differently would be a second retry model for the same
 * client to get wrong.
 *
 * <p><b>What the server decides and the client cannot.</b> Every field of the stored row that
 * carries a claim is set here: the source is always {@link ComplianceEventSource#SMS_PARSER},
 * because that is the only thing this route can be reporting; the action is always
 * {@link ComplianceEventAction#DISCARDED}, because there is no other outcome for a message that was
 * destroyed; the tenant comes from the gateway principal. The client contributes what only it
 * knows — that a detection happened, of which kind, and when — and nothing else.
 */
@Slf4j
@Service
public class ClientComplianceServiceImpl implements ClientComplianceService {

  /**
   * The only two event types a device may assert.
   *
   * <p>The other seven are raised by the server from things the server observed. A handset that
   * could file a {@code CONSENT_WITHDRAWN} or an {@code ERASURE_COMPLETED} could write a false
   * entry into the one table in this schema that is never purged and that a regulator reads.
   */
  private static final Set<ComplianceEventType> REPORTABLE =
      EnumSet.of(ComplianceEventType.OTP_DETECTED, ComplianceEventType.PIN_DETECTED);

  /**
   * The shape a client reference must have, enforced here rather than by {@code @Valid}.
   *
   * <p>The contract carries the same {@code @Pattern}, but cascading validation into the list would
   * make one malformed row fail the whole request with a 400 that names no event and carries no
   * results — after which the device either resends the same queue for ever or throws the lot away.
   * On this route it would throw away incidents whose source messages were destroyed on the phone
   * and cannot be reconstructed. So the list is validated per event, like everything else here, and
   * a bad row costs its own slot and nothing more.
   */
  private static final Pattern CLIENT_REF = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

  private static final String RECORDED = "RECORDED";
  private static final String DUPLICATE = "DUPLICATE";
  private static final String REJECTED = "REJECTED";

  private final InsightsProperties properties;
  private final BiComplianceEventRepository repository;
  private final ConsentService consent;
  private final DeviceIdHasher hasher;

  /**
   * Its own limiter, not the ingest one.
   *
   * <p>Constructed here rather than wired as a bean because {@link IngestRateLimiter} is a plain
   * class with no Spring annotations on purpose, and because a second bean of that type would make
   * the ingest path's injection ambiguous — a startup failure in the release this is meant not to
   * disturb. Sharing one bucket with ingest would also be wrong on its own terms: a customer whose
   * handset is filing detections would find their spend rejected.
   */
  private final IngestRateLimiter rateLimiter;

  public ClientComplianceServiceImpl(
      InsightsProperties properties,
      BiComplianceEventRepository repository,
      ConsentService consent,
      DeviceIdHasher hasher) {
    this.properties = properties;
    this.repository = repository;
    this.consent = consent;
    this.hasher = hasher;
    this.rateLimiter =
        new IngestRateLimiter(
            properties.getCompliance().getRateLimitMessages(),
            Duration.ofSeconds(
                Math.max(1, properties.getCompliance().getRateLimitWindowSeconds())));
  }

  @Override
  @Transactional
  public ComplianceEventBatchResponse record(Caller caller, ComplianceEventBatchRequest request) {
    List<ClientComplianceEvent> events = request.getEvents();
    log.info(
        "Insights device compliance report received userId={} events={}",
        caller.userId(),
        events == null ? 0 : events.size());

    requireWithinRateLimit(caller, events.size());
    consent.requireActiveConsent(caller.userId());

    String deviceIdHash = hasher.hashDeviceId(request.getDeviceId());
    OffsetDateTime earliest =
        OffsetDateTime.now(properties.zoneId())
            .minusMonths(Math.max(1, properties.getLookbackMonths()));
    OffsetDateTime latest =
        OffsetDateTime.now(properties.zoneId())
            .plusMinutes(Math.max(0, properties.getCompliance().getClockSkewMinutes()));

    // One round trip for the whole batch rather than one per event, and -- more importantly -- a
    // replayed queue is answered without attempting a single insert. The unique index would
    // otherwise throw mid-batch and poison the persistence context, taking the good rows with it.
    //
    // This is a read-then-write without a lock, so two genuinely simultaneous requests carrying
    // the same reference can both pass it and collide on ux_bi_ce_tenant_client_ref in
    // PostgreSQL. That loses the batch, not the incident: nothing is committed, the device is
    // told the request failed, and its retry finds the reference already recorded and reports it
    // as a duplicate. Locking a table whose purpose is to be appended to would cost more than a
    // race this narrow is worth -- the same trade ConsentServiceImpl makes on grant.
    Set<String> alreadyRecorded = existingHashes(caller, deviceIdHash, events);

    List<ComplianceEventResultItem> results = new ArrayList<>(events.size());
    List<BiComplianceEvent> toWrite = new ArrayList<>(events.size());
    Set<String> seenInThisBatch = new HashSet<>();
    int recorded = 0;
    int duplicates = 0;
    int rejected = 0;

    for (ClientComplianceEvent event : events) {
      String clientRef = event.getClientRef();

      if (clientRef == null || !CLIENT_REF.matcher(clientRef).matches()) {
        // No usable reference means no duplicate suppression for this row, so recording it would
        // make every later retry a fresh incident. Reported against a null clientRef, which is
        // what the device sent.
        results.add(reject(clientRef, ErrorCode.INSIGHTS_COMPLIANCE_EVENT_INVALID));
        rejected++;
        continue;
      }

      if (event.getDetectedAt() == null) {
        results.add(reject(clientRef, ErrorCode.INSIGHTS_COMPLIANCE_TIMESTAMP_INVALID));
        rejected++;
        continue;
      }

      ComplianceEventType type = reportableType(event.getEventType());
      if (type == null) {
        results.add(reject(clientRef, ErrorCode.INSIGHTS_COMPLIANCE_EVENT_NOT_REPORTABLE));
        rejected++;
        continue;
      }

      OffsetDateTime detectedAt = event.getDetectedAt();
      if (detectedAt.isBefore(earliest) || detectedAt.isAfter(latest)) {
        results.add(reject(clientRef, ErrorCode.INSIGHTS_COMPLIANCE_TIMESTAMP_INVALID));
        rejected++;
        continue;
      }

      String refHash = hasher.hashClientRef(deviceIdHash, clientRef);
      if (alreadyRecorded.contains(refHash) || !seenInThisBatch.add(refHash)) {
        results.add(
            ComplianceEventResultItem.builder().clientRef(clientRef).status(DUPLICATE).build());
        duplicates++;
        continue;
      }

      toWrite.add(
          BiComplianceEvent.builder()
              .eventType(type)
              .source(ComplianceEventSource.SMS_PARSER)
              .action(ComplianceEventAction.DISCARDED)
              .occurredAt(detectedAt)
              .deviceIdHash(deviceIdHash)
              .clientRefHash(refHash)
              .tenantId(caller.tenantId())
              .build());
      results.add(
          ComplianceEventResultItem.builder().clientRef(clientRef).status(RECORDED).build());
      recorded++;
    }

    if (!toWrite.isEmpty()) {
      // Not caught. A failure here must reach the caller: answering RECORDED for a row that was
      // never written is how an incident disappears -- the device clears its queue on the strength
      // of the answer and there is nothing left to retry from.
      repository.saveAll(toWrite);
    }

    log.info(
        "Insights device compliance report userId={} recorded={} duplicates={} rejected={}",
        caller.userId(),
        recorded,
        duplicates,
        rejected);

    return ComplianceEventBatchResponse.builder()
        .recorded(recorded)
        .duplicates(duplicates)
        .rejected(rejected)
        .results(results)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * The references this tenant has already reported, out of the ones in this batch.
   *
   * <p>Deliberately not wrapped in a catch. An earlier draft swallowed a failure here on the
   * reasoning that recording an incident twice beats losing it — but this runs inside
   * {@code record()}'s transaction, so a persistence failure has already marked it rollback-only
   * and the {@code saveAll} below would fail at commit anyway, with a confusing
   * {@code UnexpectedRollbackException} instead of the real cause. Letting it propagate is both
   * honest and consistent with the rest of this class: the device keeps its queue and retries.
   */
  private Set<String> existingHashes(
      Caller caller, String deviceIdHash, List<ClientComplianceEvent> events) {
    Collection<String> hashes =
        events.stream()
            .map(ClientComplianceEvent::getClientRef)
            .filter(ref -> ref != null && CLIENT_REF.matcher(ref).matches())
            .map(ref -> hasher.hashClientRef(deviceIdHash, ref))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    if (hashes.isEmpty()) {
      return Set.of();
    }
    return new HashSet<>(repository.findExistingClientRefHashes(caller.tenantId(), hashes));
  }

  /** The declared type if a device is allowed to assert it, otherwise null. */
  private ComplianceEventType reportableType(String declared) {
    if (declared == null || declared.isBlank()) {
      return null;
    }
    try {
      ComplianceEventType type =
          ComplianceEventType.valueOf(declared.trim().toUpperCase(Locale.ROOT));
      return REPORTABLE.contains(type) ? type : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private ComplianceEventResultItem reject(String clientRef, ErrorCode code) {
    return ComplianceEventResultItem.builder()
        .clientRef(clientRef)
        .status(REJECTED)
        .error(code.getCode())
        .build();
  }

  /**
   * The same brake ingest has, for the same reason.
   *
   * <p>A handset filing detections faster than a person receives messages is a looping client, and
   * this table is the one that is never purged — an unbounded writer into a permanent record is
   * worth stopping. No compliance event is raised for the throttle itself: that would be a writer
   * to this table triggered by writing to this table too fast.
   */
  private void requireWithinRateLimit(Caller caller, int count) {
    Instant now = Instant.now();
    rateLimiter.evictExpired(now);
    if (rateLimiter.tryConsume(caller.userId(), count, now)) {
      return;
    }
    long retryAfter = rateLimiter.retryAfterSeconds(caller.userId(), now);
    log.info(
        "Insights device compliance report throttled userId={} batchSize={} retryAfterSeconds={}",
        caller.userId(),
        count,
        retryAfter);
    throw new BudgetInsightsException(
        ErrorCode.INSIGHTS_COMPLIANCE_RATE_LIMITED, null, retryAfter);
  }
}
