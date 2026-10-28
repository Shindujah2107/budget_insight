package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Result of a draft write.
 *
 * @param item the item as it now appears in the effective view; {@code null} when the draft
 *     cancelled out (a never-published item deleted again)
 * @param changesetRevision send as {@code If-Match} on the next write
 * @param warnings things the save allowed but the Admin should know (for templates: regression
 *     results against the stored samples); they come back as publish warnings. Omitted when empty.
 */
public record AdminWriteResult<T>(
    T item,
    long changesetRevision,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<AdminValidation.Issue> warnings) {

  public AdminWriteResult(T item, long changesetRevision) {
    this(item, changesetRevision, List.of());
  }
}
