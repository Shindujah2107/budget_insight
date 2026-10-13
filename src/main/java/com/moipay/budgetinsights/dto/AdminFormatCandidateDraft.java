package com.moipay.budgetinsights.dto;

import java.util.UUID;

/**
 * The start of a template from a discovered format: a sample SMS built from the format with
 * invented values, and the template wizard's own analysis of it. The dashboard opens the existing
 * wizard with both; from there the admin highlights, generates, saves and publishes as usual.
 *
 * @param sample invented values in the format's shapes ("Sample Text", 1,234.56, 05/03/26…)
 */
public record AdminFormatCandidateDraft(
    UUID candidateId, String bankCode, String sample, AdminTemplateAnalysis analysis) {}
