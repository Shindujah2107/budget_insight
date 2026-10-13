package com.moipay.budgetinsights.dto;

import java.time.OffsetDateTime;

/**
 * A message type as the Admin Dashboard lists it.
 *
 * @param templates templates of any bank that use this type, drafts included (a type in use can be
 *     edited and turned off, never deleted)
 * @param activeTemplates of those, the ones that read SMS (or will after publish)
 * @param builtIn {@code true} for types added by the migration rather than by a Super Admin
 */
public record AdminMessageTypeItem(
    String code,
    String displayName,
    String logicalType,
    String defaultDirection,
    String defaultInstrument,
    String fixedCategoryCode,
    boolean active,
    int templates,
    int activeTemplates,
    boolean builtIn,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
