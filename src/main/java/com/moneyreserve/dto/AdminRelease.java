package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One published reference release (Change history).
 *
 * @param publishedBy the Super Admin who published ({@code staffId}, {@code displayName} when
 *     known); {@code null} for the Flyway seed release
 * @param summary counts per item type and operation
 * @param diff before/after of every change; only on the single-release read
 */
public record AdminRelease(
    int version,
    OffsetDateTime publishedAt,
    Publisher publishedBy,
    String publishedByRole,
    String reason,
    int changeCount,
    JsonNode summary,
    @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode diff) {

  /**
   * Who published. The service holds staff user ids only; {@code displayName} is left out until a
   * staff directory is available to resolve it (the UI shows the id meanwhile).
   *
   * <p>{@code staffId}, not {@code userId} as the architecture doc's R1 row wrote it: no Insights
   * DTO may carry a field named {@code userId} ({@code InsightsDtoArchitectureTest}), and the doc
   * itself forbids {@code userId} in any response (§3.1). This is the publishing Super Admin's
   * staff id, never a customer's.
   */
  public record Publisher(
      UUID staffId, @JsonInclude(JsonInclude.Include.NON_NULL) String displayName) {

    public static Publisher of(UUID staffId) {
      return staffId == null ? null : new Publisher(staffId, null);
    }
  }
}
