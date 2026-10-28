package com.moneyreserve.dto;

/**
 * Result of restoring an earlier release: the drafts that bring the configuration back to it.
 * Nothing is live until those drafts are published.
 */
public record AdminRestoreResult(long revision, int changesCreated, int restoredToVersion) {}
