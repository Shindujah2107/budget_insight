package com.moipay.budgetinsights.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One SMS format for the admin. {@code skeleton} is the shape only, e.g. {@code Dear Customer, bill
 * Payment made from {W} to {W} for LKR {AMT} on {DATE:dd Mmmm yyyy}}.
 *
 * @param coveredByTemplateId a live template that already reads this format, if one does now
 */
public record AdminFormatCandidate(
    UUID id,
    String senderId,
    String bankCode,
    String bankName,
    String skeleton,
    int distinctUsers,
    int samples,
    OffsetDateTime firstSeenAt,
    OffsetDateTime lastSeenAt,
    String status,
    String rejectReason,
    UUID templateId,
    UUID coveredByTemplateId) {}
