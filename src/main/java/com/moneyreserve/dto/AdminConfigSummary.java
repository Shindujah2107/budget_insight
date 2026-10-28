package com.moneyreserve.dto;

/** Tab counters and the pending-changes bar. Counts are of items that exist after publish. */
public record AdminConfigSummary(
    int categories,
    int merchantRules,
    int senders,
    int senderBanks,
    int templates,
    int templateBanks,
    int releaseVersion,
    int pendingChanges,
    long changesetRevision) {}
