package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiDataExport;
import com.moipay.budgetinsights.domain.BiRawMessage;
import com.moipay.budgetinsights.domain.ComplianceEventAction;
import com.moipay.budgetinsights.domain.ComplianceEventSource;
import com.moipay.budgetinsights.domain.ComplianceEventType;
import com.moipay.budgetinsights.dto.DataErasureResponse;
import com.moipay.budgetinsights.dto.DataExportResponse;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiDataExportRepository;
import com.moipay.budgetinsights.service.DataRightsService;
import com.moipay.budgetinsights.service.FormatDiscoveryService;
import com.moipay.budgetinsights.service.InsightsComplianceService;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Export and erasure, driven from one list.
 *
 * <p>{@link #CUSTOMER_DATA} is the definition of "everything this service holds about a customer".
 * Export reads it, erasure deletes it, and the integration test asserts both against the same
 * fixture. A table added to the schema and forgotten here is missing from an export and survives an
 * erasure — one list makes that one mistake rather than two independent ones, and the test that
 * seeds every table catches it.
 *
 * <p>The list is in <b>foreign-key-safe deletion order</b>: children before parents. Export does
 * not care about order and iterates the same list anyway, because two orders would be two things
 * to keep right.
 *
 * <p><b>{@code bi_compliance_event} is neither exported nor erased, and not as a carve-out.</b> The
 * table has no {@code user_id} column. It records that a detection happened — type, time, source,
 * action, a hashed device id — and never to whom. There is no row in it belonging to this customer
 * to hand over or to remove, which is precisely why it can be the evidence that the erasure
 * happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataRightsServiceImpl implements DataRightsService {

  private final EntityManager entityManager;
  private final BiDataExportRepository exports;
  private final InsightsComplianceService compliance;
  private final InsightsProperties properties;
  private final ObjectMapper objectMapper;
  private final FormatDiscoveryService formatDiscovery;

  /** The export document's shape version, so a stored document is never read as a newer one. */
  private static final int SCHEMA_VERSION = 1;

  /**
   * One table's worth of "the customer's data".
   *
   * @param label what it is called in the export document
   * @param entity the JPA entity name
   * @param owner the JPQL predicate that selects this customer's rows, against alias {@code x}
   * @param exported whether it belongs in a data-subject export
   */
  private record CustomerTable(String label, String entity, String owner, boolean exported) {}

  private static final String BY_USER = "x.userId = :userId";

  private static final List<CustomerTable> CUSTOMER_DATA =
      List.of(
          // Children first. Audit rows carry no user_id of their own; they belong to the customer
          // only through the transaction they describe.
          new CustomerTable(
              "transactionAudit",
              "BiTransactionAudit",
              "x.transactionId IN (SELECT t.id FROM BiTransaction t WHERE t.userId = :userId)",
              true),
          new CustomerTable(
              "budgetCategoryLimits",
              "BiBudgetCategoryLimit",
              "x.budgetId IN (SELECT b.id FROM BiBudget b WHERE b.userId = :userId)",
              true),
          new CustomerTable("budgetAlerts", "BiBudgetAlert", BY_USER, true),
          // Derived and reconstructible from the rows below it. Erased so nothing survives, not
          // exported because handing someone a cache of their own figures is noise, not data.
          new CustomerTable("insightCache", "BiInsightCache", BY_USER, false),
          // Exported as metadata only -- see rawMessageMetadata.
          new CustomerTable("rawMessages", "BiRawMessage", BY_USER, true),
          new CustomerTable("deviceSyncState", "BiDeviceSyncState", BY_USER, true),
          // Erased but never exported: an export of the customer's previous exports would nest
          // their whole history inside itself, once per request they ever made.
          new CustomerTable("dataExports", "BiDataExport", BY_USER, false),
          // Parents last.
          new CustomerTable("transactions", "BiTransaction", BY_USER, true),
          new CustomerTable("accounts", "BiAccount", BY_USER, true),
          new CustomerTable("budgets", "BiBudget", BY_USER, true),
          new CustomerTable("consentHistory", "BiConsent", BY_USER, true));

  // ---------------------------------------------------------------------------------
  // Export
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public DataExportResponse requestExport(Caller caller) {
    UUID userId = caller.userId();
    OffsetDateTime now = OffsetDateTime.now();

    String document = buildDocument(userId, now);
    byte[] bytes = document.getBytes(StandardCharsets.UTF_8);

    BiDataExport row =
        exports.save(
            BiDataExport.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .tenantId(caller.tenantId())
                .document(document)
                .sizeBytes(bytes.length)
                .requestedAt(now)
                .expiresAt(now.plusHours(retentionHours()))
                .build());

    // Recorded immediately, not after commit: the event says the customer asked, and they asked
    // whether or not the build that follows succeeds. The erasure event below is the opposite
    // case and is handled the opposite way.
    compliance.raise(
        ComplianceEventType.EXPORT_REQUESTED,
        ComplianceEventSource.APP,
        ComplianceEventAction.RECORDED,
        null,
        caller.tenantId());

    log.info(
        "Insights export prepared userId={} exportId={} bytes={} expires={}",
        userId,
        row.getId(),
        bytes.length,
        row.getExpiresAt());

    return DataExportResponse.builder()
        .exportId(row.getId())
        .requestedAt(row.getRequestedAt())
        .expiresAt(row.getExpiresAt())
        .sizeBytes(row.getSizeBytes())
        .downloadPath("/api/insights/data/export/" + row.getId())
        .build();
  }

  @Override
  @Transactional(readOnly = true)
  public String downloadExport(Caller caller, UUID exportId) {
    BiDataExport row =
        exports
            .findByIdAndUserId(exportId, caller.userId())
            .orElseThrow(
                () -> {
                  log.info(
                      "Insights export download refused userId={} exportId={}: not found",
                      caller.userId(),
                      exportId);
                  return new BudgetInsightsException(ErrorCode.INSIGHTS_EXPORT_NOT_FOUND);
                });

    // Checked here as well as purged by the job. The job runs on a schedule; a row that expired a
    // minute ago is still in the table, and the customer should not be able to download it
    // because the cleaner has not come round yet.
    if (!row.isAvailableAt(OffsetDateTime.now())) {
      log.info(
          "Insights export download refused userId={} exportId={}: expired at {}",
          caller.userId(),
          exportId,
          row.getExpiresAt());
      throw new BudgetInsightsException(ErrorCode.INSIGHTS_EXPORT_EXPIRED);
    }
    log.info("Insights export downloaded userId={} exportId={}", caller.userId(), exportId);
    return row.getDocument();
  }

  private String buildDocument(UUID userId, OffsetDateTime now) {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("schemaVersion", SCHEMA_VERSION);
    root.put("exportedAt", now.toString());
    root.put("service", "MoiPay Budget Insights");
    root.put(
        "note",
        "Everything Budget Insights holds about you. Compliance events are not included: that "
            + "table records that a detection happened, never whose it was, so it holds no row "
            + "belonging to you.");

    for (CustomerTable table : CUSTOMER_DATA) {
      if (!table.exported()) {
        continue;
      }
      List<?> rows = rowsFor(table, userId);
      // Typed explicitly rather than inlined: valueToTree is generic in its return type, and in a
      // ternary beside ArrayNode the inference is not obvious to a reader even where it compiles.
      JsonNode section =
          "rawMessages".equals(table.label())
              ? rawMessageMetadata(rows)
              : objectMapper.valueToTree(rows);
      root.set(table.label(), section);
    }

    try {
      return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("Could not serialise the Budget Insights export", ex);
    }
  }

  /**
   * Raw messages, described but never reproduced.
   *
   * <p>{@code bi_raw_message.ciphertext} is the one column in this service that could hold the text
   * of a bank message. Serialising the entity would put it in the export in the clear the moment
   * anything decrypted it, and in a file the customer is invited to download and keep. What the
   * customer is owed is the knowledge that the row exists, which bank it came from, when it
   * arrived and when it expires. That is what this returns.
   */
  private ArrayNode rawMessageMetadata(List<?> rows) {
    ArrayNode array = objectMapper.createArrayNode();
    for (Object row : rows) {
      BiRawMessage message = (BiRawMessage) row;
      ObjectNode node = array.addObject();
      node.put("id", String.valueOf(message.getId()));
      node.put("bankCode", message.getBankCode());
      node.put(
          "parseStatus",
          message.getParseStatus() == null ? null : message.getParseStatus().name());
      node.put("receivedAt", String.valueOf(message.getReceivedAt()));
      node.put("ttlExpiresAt", String.valueOf(message.getTtlExpiresAt()));
      node.put("messageContent", "not included — stored encrypted, never exported");
    }
    return array;
  }

  // ---------------------------------------------------------------------------------
  // Erasure
  // ---------------------------------------------------------------------------------

  @Override
  @Transactional
  public DataErasureResponse erase(Caller caller) {
    UUID userId = caller.userId();
    Map<String, Integer> removed = new LinkedHashMap<>();
    long total = 0;

    for (CustomerTable table : CUSTOMER_DATA) {
      int count =
          entityManager
              // The entity name and predicate are compile-time constants from CUSTOMER_DATA, never
              // caller input; only :userId is bound, and it is bound rather than interpolated.
              .createQuery("DELETE FROM " + table.entity() + " x WHERE " + table.owner())
              .setParameter("userId", userId)
              .executeUpdate();
      removed.put(table.label(), count);
      total += count;
    }

    // SMS format discovery samples carry no user_id, only an HMAC of it, so they cannot sit in
    // CUSTOMER_DATA's JPQL list; they are found by recomputing the HMAC instead. Not exported:
    // a sample is the shape of a bank's message, nothing the customer wrote or owns.
    int formatSamples = formatDiscovery.forget(userId);
    removed.put("formatSamples", formatSamples);
    total += formatSamples;

    // Bulk deletes go straight to the database and leave the persistence context holding rows that
    // no longer exist. Anything read before this call would otherwise still be returned from the
    // first-level cache inside the same transaction.
    entityManager.clear();

    // Written after commit, unlike every other compliance event in this service. The others record
    // that something happened and must survive a rollback of it -- an OTP was still detected even
    // if the message was rejected. This one claims the erasure completed, so if the transaction
    // rolls back it must not be written at all: a record of an erasure that did not happen is
    // worse than no record.
    registerErasureEvent(caller);

    log.info(
        "Insights erasure removed {} rows across {} tables {}", total, removed.size(), removed);

    return DataErasureResponse.builder()
        .erasedAt(OffsetDateTime.now())
        .rowsRemoved(total)
        .removedByTable(removed)
        .retained("bi_compliance_event — holds no user_id, so no row in it is yours to erase")
        .build();
  }

  private void registerErasureEvent(Caller caller) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      raiseErasureCompleted(caller);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            raiseErasureCompleted(caller);
          }
        });
  }

  private void raiseErasureCompleted(Caller caller) {
    try {
      compliance.raise(
          ComplianceEventType.ERASURE_COMPLETED,
          ComplianceEventSource.APP,
          ComplianceEventAction.RECORDED,
          null,
          caller.tenantId());
    } catch (RuntimeException ex) {
      // Thrown from afterCommit, this would surface as a 500 on a request whose erasure has
      // already committed and cannot be undone -- telling the customer their data is still there
      // when it is gone. That is the more harmful of the two wrong answers, so the response
      // stands. ERROR rather than WARN because a missing ERASURE_COMPLETED is a real hole in the
      // evidence trail and somebody has to reconcile it by hand.
      log.error(
          "Insights erasure completed but its compliance event could not be written. The data is "
              + "gone; the record of it is not. Reconcile bi_compliance_event manually.",
          ex);
    }
  }

  // ---------------------------------------------------------------------------------

  private List<?> rowsFor(CustomerTable table, UUID userId) {
    return entityManager
        .createQuery("SELECT x FROM " + table.entity() + " x WHERE " + table.owner())
        .setParameter("userId", userId)
        .getResultList();
  }

  private int retentionHours() {
    return Math.max(1, properties.getExport().getRetentionHours());
  }
}
