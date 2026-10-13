package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.moipay.budgetinsights.domain.BiConfigChange;
import com.moipay.budgetinsights.domain.BiConfigChangeset;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiConfigChangeRepository;
import com.moipay.budgetinsights.repository.BiConfigChangesetRepository;
import com.moipay.budgetinsights.service.AdminBankService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes drafts into the one shared admin changeset.
 *
 * <p>Rules: one pending change per item; editing an item again replaces its change; editing it
 * back to its published values drops the change; deleting a never-published draft drops it. Every
 * write bumps the revision, which callers pin with {@code If-Match}. Callers open the transaction;
 * {@link #begin} locks the revision row for its duration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminDrafts {

  private final BiConfigChangeRepository changes;
  private final BiConfigChangesetRepository changeset;
  private final AdminEffectiveView view;
  // A provider, not the service: the bank service drafts bank edits through this class.
  private final ObjectProvider<AdminBankService> bankRegistry;

  /** Locks the revision counter and checks the caller's {@code If-Match}. Call first. */
  @Transactional(propagation = Propagation.MANDATORY)
  public long begin(Long ifMatch) {
    long current = counter().getRevision();
    if (ifMatch != null && ifMatch != current) {
      log.info("bi-admin draft refused: stale If-Match revision={} sent={}", current, ifMatch);
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_CHANGESET_STALE,
          "The configuration changed since you loaded it (revision "
              + current
              + ", you had "
              + ifMatch
              + "). Reload and try again.");
    }
    return current;
  }

  /**
   * Records the desired state of one item.
   *
   * @param published the item's published values, {@code null} for a new item
   * @param desired its values after publish; ignored for DELETE
   * @return the change now pending for the item; empty when the draft cancelled out
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<BiConfigChange> draft(
      ConfigEntityType entity,
      UUID targetId,
      ConfigChangeOp op,
      Object published,
      Object desired,
      UUID userId) {
    OffsetDateTime now = OffsetDateTime.now();
    JsonNode before = view.tree(published);
    JsonNode payload = op == ConfigChangeOp.DELETE ? null : view.tree(desired);
    Optional<BiConfigChange> existing = changes.findByEntityTypeAndTargetId(entity, targetId);

    if (existing.isPresent()) {
      BiConfigChange held = existing.get();
      if (held.getOp() == ConfigChangeOp.CREATE) {
        if (op == ConfigChangeOp.DELETE || op == ConfigChangeOp.RETIRE) {
          changes.delete(held);
          logDraft("dropped (never published)", entity, targetId, op, userId);
          return Optional.empty();
        }
        logDraft("updated", entity, targetId, ConfigChangeOp.CREATE, userId);
        return Optional.of(save(held, ConfigChangeOp.CREATE, payload, userId, now));
      }
      if (cancelsOut(op, payload, view.read(held.getBeforeState()))) {
        changes.delete(held);
        logDraft("dropped (back to published values)", entity, targetId, op, userId);
        return Optional.empty();
      }
      logDraft("replaced", entity, targetId, op, userId);
      return Optional.of(save(held, op, payload, userId, now));
    }

    if (published != null && cancelsOut(op, payload, before)) {
      logDraft("not needed (same as published)", entity, targetId, op, userId);
      return Optional.empty();
    }
    logDraft("created", entity, targetId, op, userId);
    BiConfigChange created = new BiConfigChange();
    created.setId(UUID.randomUUID());
    created.setEntityType(entity);
    created.setTargetId(targetId);
    created.setBeforeState(before == null ? null : before.toString());
    created.setCreatedBy(userId);
    created.setCreatedAt(now);
    return Optional.of(save(created, op, payload, userId, now));
  }

  /**
   * Bumps the revision after a write; returns the new value. Also removes banks / organisations
   * that were created with a sender draft which no longer exists (discarded, or deleted before
   * publish).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long commit() {
    changes.flush();
    bankRegistry.getObject().purgeOrphanPending();
    BiConfigChangeset row = counter();
    row.setRevision(row.getRevision() + 1);
    row.setUpdatedAt(OffsetDateTime.now());
    changeset.save(row);
    log.info("bi-admin changeset revision now {}", row.getRevision());
    return row.getRevision();
  }

  /** Entity, id and operation only: draft values can hold names an admin typed. */
  private static void logDraft(
      String outcome, ConfigEntityType entity, UUID targetId, ConfigChangeOp op, UUID userId) {
    log.info(
        "bi-admin draft {} entity={} op={} target={} by={}", outcome, entity, op, targetId, userId);
  }

  public long revision() {
    return changeset.findById((short) 1).map(BiConfigChangeset::getRevision).orElse(0L);
  }

  private BiConfigChangeset counter() {
    return changeset
        .lockCounter()
        .orElseGet(
            () -> {
              // Only on a schema built without V1.5.0's seed row (local H2).
              BiConfigChangeset row = new BiConfigChangeset();
              row.setId((short) 1);
              row.setRevision(0L);
              row.setUpdatedAt(OffsetDateTime.now());
              return changeset.save(row);
            });
  }

  private BiConfigChange save(
      BiConfigChange change,
      ConfigChangeOp op,
      JsonNode payload,
      UUID userId,
      OffsetDateTime now) {
    change.setOp(op);
    change.setPayload(payload == null ? null : payload.toString());
    change.setUpdatedBy(userId);
    change.setUpdatedAt(now);
    return changes.save(change);
  }

  private static boolean cancelsOut(ConfigChangeOp op, JsonNode payload, JsonNode before) {
    return (op == ConfigChangeOp.UPDATE
            || op == ConfigChangeOp.ACTIVATE
            || op == ConfigChangeOp.DEACTIVATE)
        && payload != null
        && payload.equals(before);
  }
}
