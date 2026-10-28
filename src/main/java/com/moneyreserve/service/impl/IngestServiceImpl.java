package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiDeviceSyncState;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.ComplianceEventSource;
import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.IngestBatchRequest;
import com.moneyreserve.dto.IngestBatchResponse;
import com.moneyreserve.dto.IngestMessage;
import com.moneyreserve.dto.IngestResultItem;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAccountRepository;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiDeviceSyncStateRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.BudgetAlertService;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import com.moneyreserve.service.ConsentService;
import com.moneyreserve.service.IngestService;
import com.moneyreserve.service.InsightsComplianceService;
import com.moneyreserve.service.ingest.IdempotencyResolver;
import com.moneyreserve.service.ingest.IngestRateLimiter;
import com.moneyreserve.service.ingest.OtpGuard;
import com.moneyreserve.service.ingest.PayloadNormaliser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ingest pipeline (R4).
 *
 * <p>Implements §16 of the implementation plan in the order it specifies. Two of those nine steps
 * are deliberately absent: threshold alerts belong to R6, and there is no outbox fan-out, because
 * nothing would consume it.
 *
 * <p><b>Two passes, and the split is the point.</b> Pass one guards and normalises every message
 * with no database access whatsoever; pass two writes. §16 puts the OTP re-check second, before
 * anything that touches the database, and a single loop that read the ledger to look up idempotency
 * keys taken from unguarded payload would have broken that ordering even though it wrote nothing.
 * Splitting the passes makes the claim true rather than nearly true.
 *
 * <p><b>A batch is one transaction, but a message is not one outcome.</b> Consent and the rate
 * limit refuse the whole batch and write nothing. Everything else is decided per message and
 * reported in the result set, because a device holding forty good messages and one its parser
 * mangled must not be told to resend all forty — it will, repeatedly, and the mangled one will fail
 * again every time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestServiceImpl implements IngestService {

  private final InsightsProperties properties;
  private final ConsentService consent;
  private final InsightsComplianceService compliance;
  private final CategorisationService categorisation;
  private final BudgetAlertService budgetAlerts;
  private final IngestRateLimiter rateLimiter;
  private final BiTransactionRepository transactions;
  private final BiAccountRepository accounts;
  private final BiInsightCacheRepository caches;
  private final BiDeviceSyncStateRepository syncStates;
  private final BiBankParserTemplateRepository templates;
  private final ObjectProvider<IngestService> self;

  private static final String ACCEPTED = "ACCEPTED";
  private static final String DUPLICATE = "DUPLICATE";
  private static final String REJECTED = "REJECTED";

  /**
   * One message that has passed the guard and the normaliser, ready to be written.
   *
   * <p>{@code index} carries its place in the submitted batch, because the result set must come
   * back in the order the device sent it — that ordering is what lets a device match results to the
   * messages still in its outbox.
   */
  private record Prepared(
      int index,
      String deviceKey,
      String storedKey,
      String bankCode,
      String messageType,
      InstrumentType instrument,
      TxnDirection direction,
      String currency,
      BigDecimal amount,
      LocalDate txnDate,
      String merchant,
      String maskedTail,
      UUID parserTemplateId,
      OffsetDateTime parsedAt) {}

  // ---------------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------------

  /**
   * Ingests one batch, retrying once on a uniqueness collision.
   *
   * <p><b>The rate limit is consumed here, outside the retried unit.</b> The limiter is an
   * in-memory counter and knows nothing about transactions, so a check inside {@code ingestOnce}
   * would charge a rolled-back batch and then charge the retry again — two hundred messages
   * counted as four hundred, and eventually a legitimate device throttled with a permanent
   * {@code INGEST_RATE_LIMITED} incident recorded against it for traffic it never sent.
   */
  private static final java.util.regex.Pattern BANK_CODE_SHAPE =
      java.util.regex.Pattern.compile("^[0-9]{4}$");
  private static final java.util.regex.Pattern PLATFORM_SHAPE =
      java.util.regex.Pattern.compile("^[A-Za-z_]{1,16}$");
  private static final java.util.regex.Pattern TYPE_SHAPE =
      java.util.regex.Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

  /**
   * A device-supplied reference code, or "?" when it does not look like one. These fields are logged
   * before (and after) the OTP guard has looked at them, and a defective client could put message
   * text in any of them; only values that are plainly codes are written to the log.
   */
  private static String safeCode(String raw, java.util.regex.Pattern shape) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim();
    return shape.matcher(v).matches() ? v : "?";
  }

  @Override
  public IngestBatchResponse ingest(Caller caller, IngestBatchRequest request) {
    List<IngestMessage> messages =
        request.getMessages() == null ? List.of() : request.getMessages();
    // Written before consent and the rate limit are checked, so "did the phone call us at all"
    // has an answer even when the batch is then refused. Counts and codes only.
    log.info(
        "Insights ingest received userId={} device={} platform={} messages={} deviceTemplateVersion={} banks={}",
        caller.userId(),
        deviceFingerprint(request.getDeviceId()),
        safeCode(request.getPlatform(), PLATFORM_SHAPE),
        messages.size(),
        request.getTemplateVersion(),
        messages.stream()
            .filter(java.util.Objects::nonNull)
            .map(m -> safeCode(m.getBankCode(), BANK_CODE_SHAPE) + "/" + safeCode(m.getMessageType(), TYPE_SHAPE))
            .distinct()
            .toList());
    requireWithinRateLimit(caller, request.getDeviceId(), messages.size());
    try {
      return self.getObject().ingestOnce(caller, request);
    } catch (DataIntegrityViolationException ex) {
      // Two batches from the same device crossed, or the same account tail was created twice at
      // once. The rows the winner wrote are now visible, so the second attempt's pre-read finds
      // them and reports duplicates instead of colliding. Once only: a second collision is not a
      // race, it is a defect, and it should surface.
      log.info(
          "Concurrent ingest for the same device userId={} device={}; retrying the batch once",
          caller.userId(),
          deviceFingerprint(request.getDeviceId()));
      return self.getObject().ingestOnce(caller, request);
    }
  }

  @Override
  @Transactional
  public IngestBatchResponse ingestOnce(Caller caller, IngestBatchRequest request) {

    UUID userId = caller.userId();
    String deviceId = request.getDeviceId();
    List<IngestMessage> messages =
        request.getMessages() == null ? List.of() : request.getMessages();

    // ---- Step 1. Consent, before anything reads a bank message.
    consent.requireActiveConsent(userId);

    TxnSource source = sourceOf(request.getPlatform());
    IngestResultItem[] results = new IngestResultItem[messages.size()];

    // ---- Steps 2 and 3, for the whole batch, with no database access.
    List<Prepared> prepared = guardAndNormalise(caller, messages, deviceId, results);

    // ---- Steps 4 to 7.
    Outcome outcome = write(caller, prepared, results, source);

    int serverTemplateVersion = templates.findCurrentTemplateVersion();
    recordDeviceProgress(userId, deviceId, outcome.accepted, request.getTemplateVersion());

    // The one line an operator reads to answer "did that device's queue actually land". Counts
    // only, which is also all that may be said about it.
    log.info(
        "Insights ingest batch userId={} device={} submitted={} accepted={} duplicates={} rejected={}",
        userId,
        deviceFingerprint(deviceId),
        messages.size(),
        outcome.accepted,
        outcome.duplicates,
        outcome.rejected);

    transactions.flush();

    return IngestBatchResponse.builder()
        .accepted(outcome.accepted)
        .duplicates(outcome.duplicates)
        .rejected(outcome.rejected)
        // Arrays.asList and not List.of: List.of(E...) against an array argument sits next to
        // List.of(E e1) in overload resolution, and which one wins depends on the target type
        // being inferred exactly as intended. Arrays.asList has no such ambiguity, and List.of
        // would also reject a null slot -- which cannot happen here, but would be a 500 rather
        // than a gap if it ever did.
        .results(Arrays.asList(results))
        .currentTemplateVersion(
            staleTemplateVersion(request.getTemplateVersion(), serverTemplateVersion))
        .build();
  }

  // ---------------------------------------------------------------------------------
  // Pass one — guard and normalise. Nothing here touches the database.
  // ---------------------------------------------------------------------------------

  private List<Prepared> guardAndNormalise(
      Caller caller, List<IngestMessage> messages, String deviceId, IngestResultItem[] results) {

    LocalDate today = LocalDate.now(properties.zoneId());
    List<Prepared> prepared = new ArrayList<>(messages.size());

    for (int index = 0; index < messages.size(); index++) {
      IngestMessage message = messages.get(index);
      String deviceKey = message == null ? null : message.getIdempotencyKey();
      try {
        if (message == null) {
          // A null element survives @Valid on the list, and an unguarded one here is a 500 on a
          // batch that is otherwise fine.
          throw new BudgetInsightsException(ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID);
        }

        // ---- Step 2. The OTP re-check, before validation and before any read.
        guardOtp(message, deviceId);

        // ---- Step 3. Shape, currency, direction and date bounds.
        prepared.add(
            new Prepared(
                index,
                deviceKey,
                IdempotencyResolver.forIngestedMessage(
                    PayloadNormaliser.requireIdempotencyKey(message.getIdempotencyKey())),
                PayloadNormaliser.requireBankCode(message.getBankCode()),
                requireInScope(message.getMessageType()),
                PayloadNormaliser.requireInstrumentType(message.getInstrumentType()),
                PayloadNormaliser.direction(message.getDirection()),
                PayloadNormaliser.requireCurrency(message.getCurrency(), properties.getCurrency()),
                PayloadNormaliser.requireAmount(message.getAmount()),
                PayloadNormaliser.requireUsableDate(
                    message.getTxnDate(), today, properties.getLookbackMonths()),
                PayloadNormaliser.merchant(message.getMerchant()),
                PayloadNormaliser.maskedTail(message.getMaskedTail()),
                message.getParserTemplateId(),
                message.getParsedAt()));

      } catch (BudgetInsightsException ex) {
        // Bank and message type are reference codes; the message's values are never logged.
        log.info(
            "Insights ingest message rejected userId={} device={} index={} code={} bank={} type={}",
            caller.userId(),
            deviceFingerprint(deviceId),
            index,
            ex.getErrorCode().getCode(),
            message == null ? null : safeCode(message.getBankCode(), BANK_CODE_SHAPE),
            message == null ? null : safeCode(message.getMessageType(), TYPE_SHAPE));
        results[index] =
            IngestResultItem.builder()
                .idempotencyKey(deviceKey)
                .status(REJECTED)
                .error(ex.getErrorCode().getCode())
                .build();
      }
    }
    return prepared;
  }

  /**
   * The server-side re-check (step 2).
   *
   * <p>Every string on the message, not only the merchant. The merchant is the field a leaked
   * message body would most plausibly end up in, but a device defect that puts it in the bank code
   * is the same leak, and a guard that only looks where it expects trouble is not defence in depth.
   *
   * <p>The compliance event is raised before the refusal is thrown, and in its own transaction, so
   * the incident survives the rejection rolling the batch's work back.
   */
  private void guardOtp(IngestMessage message, String deviceId) {
    boolean tripped =
        OtpGuard.trips(
            message.getMerchant(),
            message.getBankCode(),
            message.getMessageType(),
            message.getInstrumentType(),
            message.getCurrency(),
            message.getDirection(),
            message.getMaskedTail(),
            message.getIdempotencyKey());
    if (!tripped) {
      return;
    }
    compliance.raiseOtpDetected(ComplianceEventSource.SERVER, deviceId);
    // Not logged with the value, the field or the rule. The record is the compliance event.
    log.info(
        "Insights ingest message rejected by the server-side OTP guard device={}",
        deviceFingerprint(deviceId));
    throw new BudgetInsightsException(ErrorCode.INSIGHTS_OTP_PAYLOAD_REJECTED);
  }

  /**
   * Refuses a message type the deployment has not opted into.
   *
   * <p>Whether account-balance messages are in scope is an open Compliance decision (D-3, R-9 in
   * the plan). Keeping the accepted set in configuration is what makes that decision a flag change
   * rather than rework — and it means a device that starts sending a new message type cannot widen
   * what this service stores by doing so.
   */
  private String requireInScope(String rawMessageType) {
    String messageType = PayloadNormaliser.requireMessageType(rawMessageType);
    List<String> allowed = properties.getIngest().getAcceptedMessageTypes();
    if (allowed == null || allowed.isEmpty() || allowed.contains(messageType)) {
      return messageType;
    }
    throw new BudgetInsightsException(
        ErrorCode.INSIGHTS_INGEST_MESSAGE_INVALID,
        "This kind of message is not in scope for Budget Insights.");
  }

  // ---------------------------------------------------------------------------------
  // Pass two — write
  // ---------------------------------------------------------------------------------

  private static final class Outcome {
    private int accepted;
    private int duplicates;
    private int rejected;
  }

  private Outcome write(
      Caller caller, List<Prepared> prepared, IngestResultItem[] results, TxnSource source) {

    UUID userId = caller.userId();
    Outcome outcome = new Outcome();
    for (IngestResultItem item : results) {
      if (item != null) {
        outcome.rejected++;
      }
    }

    Map<String, BiTransaction> alreadyIngested = existingByKey(userId, prepared);
    Set<UUID> knownTemplates = knownTemplateIds(prepared);

    Map<String, UUID> seenInBatch = new HashMap<>();
    Set<LocalDate> touchedDates = new LinkedHashSet<>();
    Map<String, BiAccount> accountsInBatch = new HashMap<>();
    int unknownTemplates = 0;

    for (Prepared message : prepared) {

      // ---- Step 5, brought forward. A key already ingested is a duplicate and nothing below
      // needs to run for it. Two checks because a queue can carry the same message twice in one
      // batch, and that collision would otherwise only surface at flush, poisoning the whole
      // transaction.
      BiTransaction existing = alreadyIngested.get(message.storedKey());
      UUID seen = seenInBatch.get(message.storedKey());
      if (existing != null || seen != null) {
        outcome.duplicates++;
        results[message.index()] =
            IngestResultItem.builder()
                .idempotencyKey(message.deviceKey())
                .status(DUPLICATE)
                // The id of the row the device's message is already represented by, whichever pass
                // created it. Without it the device cannot reconcile and will retry for ever.
                .transactionId(existing != null ? existing.getId() : seen)
                .build();
        continue;
      }

      // ---- Step 4. Account resolution from the masked tail.
      BiAccount account = resolveAccount(caller, message, accountsInBatch);

      // ---- Step 6. Categorisation. Both columns, always: auto_category_id is what the merchant
      // map is judged on later, and it has to survive the customer correcting the row.
      BiCategory category =
          categorisation.categorise(
              new CategorisationInput(message.merchant(), message.messageType(), message.direction()));

      // A template id the device made up, or one from a template that has since been retired,
      // would violate fk_bi_txn_template at flush -- which is a DataIntegrityViolationException,
      // not a BudgetInsightsException, so it would escape the per-message catch, roll back the
      // whole batch, and fail identically on the retry. The id is metadata for measuring parser
      // accuracy; it is not worth a customer's forty good messages.
      UUID templateId = message.parserTemplateId();
      if (templateId != null && !knownTemplates.contains(templateId)) {
        templateId = null;
        unknownTemplates++;
      }

      BiTransaction saved =
          transactions.save(
              BiTransaction.builder()
                  .userId(userId)
                  .accountId(account == null ? null : account.getId())
                  .amount(message.amount())
                  .currency(message.currency())
                  .direction(message.direction())
                  .merchant(message.merchant())
                  .txnDate(message.txnDate())
                  .txnTimestamp(message.parsedAt())
                  .categoryId(category.getId())
                  .autoCategoryId(category.getId())
                  .isUserCategorised(Boolean.FALSE)
                  .source(source)
                  .parserTemplateId(templateId)
                  .idempotencyKey(message.storedKey())
                  .build());

      seenInBatch.put(message.storedKey(), saved.getId());
      touchedDates.add(message.txnDate());
      outcome.accepted++;
      results[message.index()] =
          IngestResultItem.builder()
              .idempotencyKey(message.deviceKey())
              .status(ACCEPTED)
              .transactionId(saved.getId())
              .build();

      // Codes and identifiers only. No amount and no merchant: the point of confining this
      // feature's personal financial detail to one table is defeated by a log that mirrors it.
      log.info(
          "Insights message ingested txnId={} userId={} bank={} type={} categoryCode={} template={} accountMatched={}",
          saved.getId(),
          userId,
          message.bankCode(),
          message.messageType(),
          category.getCode(),
          templateId,
          account != null);
    }

    if (unknownTemplates > 0) {
      // Not an error for the customer, but it means a fleet is parsing with templates this
      // deployment does not have, which is worth finding out about before accuracy is measured.
      log.info(
          "Insights ingest dropped {} unrecognised parser template id(s) userId={}",
          unknownTemplates,
          userId);
    }

    // ---- Step 7. Cache invalidation, once for the batch rather than once per message.
    invalidate(userId, touchedDates);

    // ---- Step 8. Threshold evaluation, in this transaction so it sees the rows just written.
    // Once per distinct month rather than per date: the caps are monthly, so a batch spanning
    // thirty days of one month has one period to evaluate, not thirty. R6 (BI-25); before it this
    // step was marked as belonging to a later release.
    Set<YearMonth> months = new LinkedHashSet<>();
    for (LocalDate date : touchedDates) {
      months.add(YearMonth.from(date));
    }
    for (YearMonth month : months) {
      budgetAlerts.evaluate(caller, month.atDay(1));
    }
    return outcome;
  }

  /**
   * Finds or creates the account a message describes (step 4).
   *
   * <p>Three things decide the answer, and the third is a compliance switch rather than data.
   *
   * <p><b>No tail, no account.</b> A message that named no instrument is recorded against none.
   * {@code ux_bi_account_user_bank_tail} includes a nullable column, and in PostgreSQL nulls do not
   * collide, so a null-tailed "account" would create a fresh row on every message.
   *
   * <p><b>A card tail is dropped while {@code masked-card-to-backend-enabled} is off.</b> The
   * transaction is still ingested — the spend is real and belongs in the total — but it is recorded
   * against no account and no {@code bi_account} row is created for it. That is the reversible
   * choice: turning the flag on later starts resolving cards from the next message, and nothing has
   * to be undone. Refusing card messages outright would have made the whole release untestable
   * end to end while the flag is off, which is its default. Bank instruments are unaffected: the
   * flag is about card tails.
   */
  private BiAccount resolveAccount(
      Caller caller, Prepared message, Map<String, BiAccount> batchCache) {

    if (message.maskedTail() == null) {
      return null;
    }
    if (InstrumentType.CARD.equals(message.instrument())
        && !properties.isMaskedCardToBackendEnabled()) {
      return null;
    }

    String key = message.bankCode() + "|" + message.instrument() + "|" + message.maskedTail();
    BiAccount cached = batchCache.get(key);
    if (cached != null) {
      return cached;
    }

    Optional<BiAccount> found =
        accounts.findByUserIdAndBankCodeAndInstrumentTypeAndMaskedTailAndDeletedAtIsNull(
            caller.userId(), message.bankCode(), message.instrument(), message.maskedTail());

    BiAccount account =
        found.orElseGet(
            () ->
                accounts.save(
                    BiAccount.builder()
                        .userId(caller.userId())
                        .bankCode(message.bankCode())
                        .instrumentType(message.instrument())
                        .maskedTail(message.maskedTail())
                        .displayName(message.bankCode() + " " + message.maskedTail())
                        .isActive(Boolean.TRUE)
                        .build()));

    batchCache.put(key, account);
    return account;
  }

  /**
   * Clears every cached aggregate a message in this batch could have changed (step 7).
   *
   * <p>One call per distinct date, and one log line for the batch. The manual-entry path logs per
   * write, which is right at one transaction a time and wrong at forty.
   */
  private void invalidate(UUID userId, Set<LocalDate> dates) {
    if (dates.isEmpty()) {
      return;
    }
    int cleared = 0;
    for (LocalDate date : dates) {
      cleared += caches.invalidateCovering(userId, date);
    }
    log.info(
        "Insights aggregate cache invalidated userId={} distinctDates={} rowsCleared={}",
        userId,
        dates.size(),
        cleared);
  }

  /**
   * Advances the device's position so support can answer "did this handset sync".
   *
   * <p>{@code lastIngestSeq} counts accepted messages rather than batches. It is a progress
   * counter, not a cursor: nothing resumes from it, because the idempotency key already makes
   * resuming from the wrong place harmless.
   *
   * <p>A row created here records the version the <em>device</em> says it holds, not the server's.
   * Claiming a handset holds templates it has never been served would make the row lie about the
   * one thing support consults it for.
   */
  private void recordDeviceProgress(
      UUID userId, String deviceId, int accepted, Integer deviceTemplateVersion) {
    if (deviceId == null || deviceId.isBlank()) {
      return;
    }
    BiDeviceSyncState state =
        syncStates
            .findByUserIdAndDeviceId(userId, deviceId)
            .orElseGet(
                () ->
                    BiDeviceSyncState.builder()
                        .userId(userId)
                        .deviceId(deviceId)
                        .lastIngestSeq(0L)
                        .templateVersion(
                            deviceTemplateVersion == null ? 0 : deviceTemplateVersion)
                        .build());
    state.setLastIngestSeq(
        (state.getLastIngestSeq() == null ? 0L : state.getLastIngestSeq()) + accepted);
    state.setLastSyncedAt(OffsetDateTime.now());
    syncStates.save(state);
  }

  /**
   * Which platform's pipeline produced this batch.
   *
   * <p>Absent means Android, which is what every client built against R4 sends and what the service
   * recorded unconditionally before R6. The value is validated at the contract, so an unknown
   * string never reaches here; the fallback is for the field being missing, not for it being wrong.
   */
  private TxnSource sourceOf(String platform) {
    return "IOS".equalsIgnoreCase(platform == null ? null : platform.trim())
        ? TxnSource.SMS_IOS
        : TxnSource.SMS_ANDROID;
  }

  private void requireWithinRateLimit(Caller caller, String deviceId, int count) {
    Instant now = Instant.now();
    rateLimiter.evictExpired(now);
    if (rateLimiter.tryConsume(caller.userId(), count, now)) {
      return;
    }
    long retryAfter = rateLimiter.retryAfterSeconds(caller.userId(), now);
    compliance.raiseIngestRateLimited(deviceId);
    log.info(
        "Insights ingest throttled userId={} device={} batchSize={} retryAfterSeconds={}",
        caller.userId(),
        deviceFingerprint(deviceId),
        count,
        retryAfter);
    throw new BudgetInsightsException(ErrorCode.INSIGHTS_INGEST_RATE_LIMITED, null, retryAfter);
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  /**
   * Every key in this batch that already exists, in one query.
   *
   * <p>One round trip rather than one per message, and — more importantly — it means a replayed
   * queue is answered without attempting a single insert. An insert that violates
   * {@code ux_bi_txn_user_idem} does not fail politely: it poisons the Hibernate session and takes
   * the whole batch with it.
   *
   * <p>Built from validated keys only, which is why pass one runs first.
   */
  private Map<String, BiTransaction> existingByKey(UUID userId, List<Prepared> prepared) {
    Set<String> keys = new HashSet<>();
    for (Prepared message : prepared) {
      keys.add(message.storedKey());
    }
    if (keys.isEmpty()) {
      return Map.of();
    }
    Map<String, BiTransaction> byKey = new HashMap<>();
    for (BiTransaction txn : transactions.findAllByUserIdAndIdempotencyKeyIn(userId, keys)) {
      byKey.put(txn.getIdempotencyKey(), txn);
    }
    return byKey;
  }

  /** The parser template ids in this batch that this deployment actually has, in one query. */
  private Set<UUID> knownTemplateIds(List<Prepared> prepared) {
    Set<UUID> claimed = new HashSet<>();
    for (Prepared message : prepared) {
      if (message.parserTemplateId() != null) {
        claimed.add(message.parserTemplateId());
      }
    }
    if (claimed.isEmpty()) {
      return Set.of();
    }
    Set<UUID> known = new HashSet<>();
    for (BiBankParserTemplate template : templates.findAllById(claimed)) {
      known.add(template.getId());
    }
    return known;
  }

  /** The server's version, but only when the device is behind. Null otherwise, so the field vanishes. */
  private Integer staleTemplateVersion(Integer deviceVersion, int serverVersion) {
    int held = deviceVersion == null ? 0 : deviceVersion;
    return held < serverVersion ? serverVersion : null;
  }

  /**
   * A device identifier that is safe to put in an application log.
   *
   * <p>The raw identifier is a stable handle on one customer's handset. It belongs in the
   * compliance record, hashed, and not in the log aggregator, so the log gets a short prefix that
   * is enough to correlate two lines in one incident and not enough to follow a device around.
   */
  private String deviceFingerprint(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      return "-";
    }
    String trimmed = deviceId.trim();
    return trimmed.length() <= 6 ? trimmed : trimmed.substring(0, 6) + "…";
  }
}
