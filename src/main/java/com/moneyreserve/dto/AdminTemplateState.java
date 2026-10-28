package com.moneyreserve.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * An SMS parser template.
 *
 * <p>{@code builderSpec} holds the wizard's literal bank wording and field slots, never sample
 * text. It is {@code null} for seeded templates and templates saved in Advanced mode.
 */
public record AdminTemplateState(
    String bankCode,
    String messageType,
    String pattern,
    JsonNode fieldMap,
    int templateVersion,
    boolean active,
    JsonNode builderSpec) {

  /** A JSON {@code null} builder spec (as read back from a draft) is no spec. */
  public AdminTemplateState {
    if (builderSpec != null && (builderSpec.isNull() || builderSpec.isMissingNode())) {
      builderSpec = null;
    }
  }

  public AdminTemplateState withActive(boolean value) {
    return new AdminTemplateState(
        bankCode, messageType, pattern, fieldMap, templateVersion, value, builderSpec);
  }
}
