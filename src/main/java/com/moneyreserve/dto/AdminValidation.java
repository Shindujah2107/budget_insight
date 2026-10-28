package com.moneyreserve.dto;

import com.moneyreserve.domain.ConfigEntityType;
import java.util.List;
import java.util.UUID;

/**
 * The pre-publish checks of the whole effective configuration.
 *
 * @param errors block publishing
 * @param warnings publishing needs {@code acknowledgeWarnings=true}
 * @param checks the checklist shown on Review & publish
 */
public record AdminValidation(
    long revision, List<Issue> errors, List<Issue> warnings, List<Check> checks) {

  /** One problem, tied to the item it is about when there is one. */
  public record Issue(String code, ConfigEntityType entityType, UUID targetId, String description) {}

  /** One line of the checklist. */
  public record Check(String key, boolean passed, String description) {}

  public boolean hasErrors() {
    return !errors.isEmpty();
  }
}
