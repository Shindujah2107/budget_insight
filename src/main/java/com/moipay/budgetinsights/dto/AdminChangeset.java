package com.moipay.budgetinsights.dto;

import java.util.List;

/**
 * Everything waiting to be published.
 *
 * @param revision send as {@code changesetRevision} when publishing
 * @param baseReleaseVersion send as {@code baseReleaseVersion} when publishing
 */
public record AdminChangeset(long revision, int baseReleaseVersion, List<AdminChangeView> changes) {}
