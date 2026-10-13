package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The wizard's preview (T4): the spec that will be saved (no sample text), its text view, the
 * generated pattern and field map, and the checks. Save sends only {@code builderSpec}; the server
 * regenerates the rest from it.
 */
public record AdminTemplateGenerated(
    JsonNode builderSpec,
    String templateView,
    String pattern,
    JsonNode fieldMap,
    AdminTemplateCheck check) {}
