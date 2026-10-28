package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The receipt for a data export request (BI-27).
 *
 * <p>It carries the id and where to fetch it, never the export itself. The document is a single
 * JSON object of unbounded size; putting it inside an envelope here would mean every client that
 * only wanted to know the request succeeded had to receive the customer's whole history to find
 * out.
 *
 * <p>No field names a customer. The caller is the authenticated principal, and {@code
 * InsightsDtoArchitectureTest} fails the build on a DTO that could carry an owner identifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataExportResponse {

  /** The id to download with. */
  private UUID exportId;

  private OffsetDateTime requestedAt;

  /**
   * When the download stops being offered and the row is removed.
   *
   * <p>Returned rather than left implicit: a customer who is told their data is ready should be
   * told how long it stays ready, and a client that shows a download button needs to know when to
   * stop showing it.
   */
  private OffsetDateTime expiresAt;

  private Integer sizeBytes;

  /** Where to GET it. Relative, so it is correct behind any proxy and in local development. */
  private String downloadPath;
}
