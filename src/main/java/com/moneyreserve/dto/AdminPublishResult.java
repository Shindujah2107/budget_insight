package com.moneyreserve.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/** A new reference release: handsets download it on their next sync. */
public record AdminPublishResult(
    int version, OffsetDateTime publishedAt, int changeCount, JsonNode summary) {}
