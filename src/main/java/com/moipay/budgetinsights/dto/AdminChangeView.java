package com.moipay.budgetinsights.dto;

import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One pending change, in the words the Review & publish screen shows.
 *
 * @param label what the change is about, e.g. "Spending category · DINING_OUT"
 * @param summary what it does, e.g. "Sinhala name, icon changed"
 */
public record AdminChangeView(
    UUID changeId,
    ConfigEntityType entityType,
    UUID targetId,
    ConfigChangeOp op,
    String label,
    String summary,
    UUID createdBy,
    UUID updatedBy,
    OffsetDateTime updatedAt) {}
