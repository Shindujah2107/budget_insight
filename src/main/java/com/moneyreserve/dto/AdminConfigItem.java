package com.moneyreserve.dto;

import com.moneyreserve.domain.ConfigItemState;
import java.util.UUID;

/**
 * The common envelope of every admin configuration list item.
 *
 * @param published the live values; {@code null} for a draft that was never published
 * @param effective the values after publish
 * @param locked {@code true} for items that cannot be changed (the OTHER category)
 * @param usage entity-specific counts
 */
public record AdminConfigItem<T>(
    UUID id,
    ConfigItemState state,
    UUID pendingChangeId,
    T published,
    T effective,
    boolean locked,
    Object usage) {}
