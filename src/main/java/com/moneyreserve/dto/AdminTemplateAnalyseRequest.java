package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Template wizard, step 2 (T3): a pasted, redacted sample to analyse. The sample is read and
 * thrown away — never stored, logged or returned. Over 1,000 characters, or OTP / PIN wording,
 * is refused with {@code MR.ADM.E.018}.
 *
 * <p>This is the one Insights DTO that carries message text, deliberately: it is admin-only
 * (SUPER_ADMIN), stateless, OTP-guarded and excluded from logging (architecture doc §7.7.4).
 */
public record AdminTemplateAnalyseRequest(
    @NotBlank @Size(max = 16) String bankCode,
    @NotBlank @Size(max = 20000, message = "sample is far too long") String sample,
    /** The template being edited, if any: it may read its own sample. */
    UUID templateId) {

  /** Two-argument form kept for existing callers. */
  public AdminTemplateAnalyseRequest(String bankCode, String sample) {
    this(bankCode, sample, null);
  }
}
