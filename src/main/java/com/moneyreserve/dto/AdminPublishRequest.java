package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Publish the changeset as a new reference release.
 *
 * @param reason stored in the change history with who and when
 * @param changesetRevision the revision the Admin reviewed; 409 when it has moved
 * @param baseReleaseVersion the release the Admin reviewed against; 409 when it has moved
 * @param acknowledgeWarnings required when validation returned warnings
 */
public record AdminPublishRequest(
    @NotBlank @Size(min = 10, max = 500, message = "reason must be 10-500 characters") String reason,
    @NotNull Long changesetRevision,
    @NotNull Integer baseReleaseVersion,
    Boolean acknowledgeWarnings) {}
