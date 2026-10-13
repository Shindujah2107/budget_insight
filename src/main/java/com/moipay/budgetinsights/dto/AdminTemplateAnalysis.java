package com.moipay.budgetinsights.dto;

import com.moipay.budgetinsights.service.ingest.TemplateSampleAnalyser;
import java.util.List;
import java.util.UUID;

/**
 * What the wizard learned from a sample (T3): token and suggestion offsets, privacy warnings, and
 * whether an active template (live or draft) already reads it. No sample text.
 */
public record AdminTemplateAnalysis(
    int length,
    List<TemplateSampleAnalyser.Token> tokens,
    List<TemplateSampleAnalyser.Suggestion> suggestions,
    List<TemplateSampleAnalyser.PiiWarning> piiWarnings,
    ExistingMatch existingTemplateMatch) {

  /**
   * An active template (live or draft) that already reads the sample.
   *
   * @param sameBank true when it belongs to the bank chosen in the wizard: the same format must not
   *     be added twice, so the wizard stops and {@code /builder/generate} refuses it. False means
   *     another bank's template reads it — usually the wrong bank was picked; the wizard warns.
   * @param draft true when that template is a not-yet-published draft
   */
  public record ExistingMatch(
      UUID templateId,
      String messageType,
      String bankCode,
      String bankName,
      Integer templateVersion,
      boolean draft,
      boolean sameBank) {}
}
