package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moipay.budgetinsights.domain.ConfigItemState;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A bank as the Admin Dashboard lists it. The first two fields are the original {@code /config/banks}
 * shape, so dropdowns that read only those keep working.
 *
 * @param pending {@code true} while it was created with a sender draft that is not published yet
 *     ("New" in the dashboard); discarding that draft removes it
 * @param usage what refers to the bank — drafts included — and therefore what it may still do:
 *     {@code deletable} only when nothing ever used it, {@code deactivatable} only when no active
 *     sender or template reads its SMS
 * @param bankName / active the values after the next publish (a pending edit applied)
 * @param state PUBLISHED, or DRAFT_EDITED / DRAFT_DEACTIVATED / DRAFT_DELETED while an edit waits
 *     for publish
 * @param published the live values; differs from bankName / active only while an edit is pending
 * @param changesetRevision only on a write response: send as {@code If-Match} on the next write
 */
public record AdminBankItem(
    String bankCode,
    String bankName,
    boolean pending,
    boolean active,
    Usage usage,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    ConfigItemState state,
    UUID pendingChangeId,
    AdminBankState published,
    @JsonInclude(JsonInclude.Include.NON_NULL) Long changesetRevision) {

  public record Usage(
      int senders,
      int activeSenders,
      int templates,
      int activeTemplates,
      long customerAccounts,
      boolean deletable,
      boolean deactivatable) {}
}
