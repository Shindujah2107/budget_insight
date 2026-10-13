package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Budget Insights admin Overview (screen 01). Counts only: no amounts, no customer identifiers.
 *
 * <p>A panel whose query failed is {@code null} and is named in {@code panelErrors}, so the
 * dashboard shows an error on that panel alone.
 *
 * @param tenantScope {@code ALL}, or the tenant the counts were filtered to
 */
public record AdminOverview(
    Range range,
    String tenantScope,
    Kpis kpis,
    List<DailyCount> dailyBySource,
    List<CategoryCount> categories,
    List<BankCount> banks,
    ReferenceVersions referenceVersions,
    List<String> panelErrors) {

  public record Range(LocalDate from, LocalDate to) {}

  /** Headline numbers. A field is {@code null} when the panel it comes from failed. */
  public record Kpis(
      Long customersWithData,
      Long customersWithActiveConsent,
      Long transactionsStored,
      Double otherCategoryShare,
      Long otherCategoryCount,
      Integer banksProducing,
      Integer banksConfigured,
      List<AdminBank> banksSilent,
      OffsetDateTime lastAggregateRefresh) {}

  public record DailyCount(
      LocalDate date,
      @JsonProperty("SMS_ANDROID") long smsAndroid,
      @JsonProperty("SMS_IOS") long smsIos,
      @JsonProperty("MANUAL") long manual) {}

  /**
   * One slice of the category chart. {@code colourHex} is the category's published colour
   * ({@code #RRGGBB[AA]}), or null when none is set — the client then uses its own palette.
   */
  public record CategoryCount(String code, String name, String colourHex, long count) {}

  public record BankCount(String bankCode, String bankName, long count, double share) {}

  public record VersionCount(int version, long devices) {}

  /** Handsets by the reference version they last synced (last 90 days, all tenants). */
  public record ReferenceVersions(int current, List<VersionCount> devices) {}
}
