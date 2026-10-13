package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.moipay.budgetinsights.domain.ConfigItemState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * An SMS parser template for the admin (T1 list row, T2 detail).
 *
 * <p>The list carries no pattern or field map; {@link Detail} does (Advanced mode, screen 21). A
 * template is never edited in place: publishing an edit creates {@code nextVersion}.
 *
 * @param templateVersion the published version, {@code null} for a draft of a new template
 * @param nextVersion the version publish will create; {@code null} when nothing new is created
 * @param editorMode BUILDER when the template has a wizard spec, otherwise ADVANCED
 * @param lastChange the last draft save or publish that touched this template
 * @param bankUnread30d SMS from this bank's senders that phones could not read in the last 30 days
 *     (the SMS coverage report for the bank); null while SMS format discovery is off
 * @param templateView the wizard spec as text, e.g. {@code LKR {Amount} {Direction} ...}; builder
 *     templates only
 */
public record AdminTemplateItem(
    UUID id,
    ConfigItemState state,
    UUID pendingChangeId,
    String bankCode,
    String bankName,
    String messageType,
    Integer templateVersion,
    Integer nextVersion,
    boolean active,
    String editorMode,
    String logicalType,
    String instrumentType,
    List<String> senders,
    String templateView,
    Usage usage,
    LastChange lastChange,
    Long bankUnread30d,
    @JsonInclude(JsonInclude.Include.NON_NULL) Detail detail) {

  /**
   * Transactions parsed by any version of this bank + message type.
   *
   * @param lastReadAt when the newest of them was stored (the last SMS this template read); null
   *     when it never read one
   */
  public record Usage(long parsed30d, long parsedTotal, OffsetDateTime lastReadAt) {}

  /**
   * The last change to this template.
   *
   * @param kind {@code DRAFT} (an unpublished change; {@code by} saved it), {@code PUBLISHED}
   *     ({@code by} published release {@code releaseVersion}) or {@code SEEDED} (added by a
   *     migration; {@code by} is null)
   * @param by the staff user id, as the gateway sent it
   */
  public record LastChange(String kind, OffsetDateTime at, UUID by, Integer releaseVersion) {}

  public record Detail(
      String pattern, JsonNode fieldMap, JsonNode builderSpec, List<Version> versions) {}

  public record Version(UUID id, int templateVersion, boolean active, long parsedTotal) {}
}
