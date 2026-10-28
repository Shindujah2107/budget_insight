package com.moneyreserve.dto;

import java.util.List;

/** A configuration list plus the numbers the dashboard sends back on its next write or publish. */
public record AdminConfigList<T>(List<T> items, long changesetRevision, int releaseVersion) {}
