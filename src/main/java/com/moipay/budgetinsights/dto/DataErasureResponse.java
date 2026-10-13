package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What an erasure removed (BI-28).
 *
 * <p>The per-table counts are returned rather than a single total, and that is the point of the
 * response. "We deleted your data" is not checkable by the person who asked; a list naming every
 * table and how many rows left it is. It is also what makes the operation auditable without
 * granting anyone read access to the rows themselves — the counts say what happened, the rows are
 * gone.
 *
 * <p>Zeros are included. A table that had nothing in it is part of the answer: it says the table
 * was visited, not overlooked.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataErasureResponse {

  private OffsetDateTime erasedAt;

  private long rowsRemoved;

  /** Table label to row count, in the order the tables were cleared. */
  private Map<String, Integer> removedByTable;

  /**
   * Named so nobody has to infer it from the absence of a count.
   *
   * <p>{@code bi_compliance_event} is not erased, and it is not an exception carved out for
   * convenience: the table has no {@code user_id} column at all. It records that a detection
   * happened, not to whom, so there is no row in it that belongs to this customer to remove.
   */
  private String retained;
}
