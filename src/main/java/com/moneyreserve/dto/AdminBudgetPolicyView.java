package com.moneyreserve.dto;

import com.moneyreserve.domain.ConfigItemState;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The Budget Policy tab: the live values, the values after publish, and who last published a
 * change to them.
 *
 * @param state PUBLISHED, or DRAFT_EDITED while a change is pending
 * @param pendingChangeId the pending change, to discard it from Review &amp; publish; null when none
 * @param published the live policy
 * @param effective the policy after the next publish (equals {@code published} without a draft)
 * @param lastPublished the newest release that changed the policy; null if it was never changed
 *     from the seed
 * @param changesetRevision send as {@code If-Match} on the next write
 * @param releaseVersion the live reference release
 */
public record AdminBudgetPolicyView(
    UUID id,
    ConfigItemState state,
    UUID pendingChangeId,
    AdminBudgetPolicyState published,
    AdminBudgetPolicyState effective,
    LastPublished lastPublished,
    long changesetRevision,
    int releaseVersion) {

  public record LastPublished(int version, OffsetDateTime publishedAt, UUID publishedBy, String reason) {}
}
