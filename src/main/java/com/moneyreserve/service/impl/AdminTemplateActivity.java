package com.moneyreserve.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiReferenceRelease;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiReferenceReleaseRepository;
import com.moneyreserve.repository.BiUnmatchedSmsStatRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Activity beside each SMS template in the admin list: which release last changed it, when its
 * version row was created, and how many SMS from its bank's senders no template could read.
 *
 * <p>Everything here is read-only and cached briefly; it is guidance on the list, never used to
 * parse or store anything.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTemplateActivity {

  /** The release that last created, changed or retired a template version. */
  public record ReleaseRef(int version, OffsetDateTime publishedAt, UUID publishedBy) {}

  private static final Duration TTL = Duration.ofSeconds(60);
  static final int UNREAD_DAYS = 30;

  private final BiReferenceReleaseRepository releases;
  private final AdminReleaseVersion releaseVersion;
  private final BiBankParserTemplateRepository templates;
  private final BiUnmatchedSmsStatRepository unmatched;
  private final FormatCoverage coverage;
  private final InsightsProperties properties;
  private final ObjectMapper objectMapper;

  private volatile Snapshot<Map<UUID, ReleaseRef>> byRelease;
  private volatile Snapshot<Map<UUID, OffsetDateTime>> created;
  private volatile Snapshot<Map<String, Long>> unread;

  private record Snapshot<T>(T value, Instant at, int releaseVersion) {}

  /**
   * Template version id → the newest release whose changes name it (as the drafted id or the row
   * the publish created). Rebuilt only when a new release exists.
   */
  public Map<UUID, ReleaseRef> lastReleaseByTemplate() {
    int current = releaseVersion.current();
    Snapshot<Map<UUID, ReleaseRef>> held = byRelease;
    if (held != null && held.releaseVersion() == current) {
      return held.value();
    }
    Map<UUID, ReleaseRef> out = new HashMap<>();
    List<BiReferenceRelease> all = releases.findAll();
    all.sort(Comparator.comparing(BiReferenceRelease::getVersion));
    for (BiReferenceRelease r : all) {
      JsonNode diff;
      try {
        diff = objectMapper.readTree(r.getDiff() == null ? "[]" : r.getDiff());
      } catch (Exception ex) {
        log.warn("bi-admin release v{} diff unreadable; skipped for template activity", r.getVersion());
        continue;
      }
      ReleaseRef ref = new ReleaseRef(r.getVersion(), r.getPublishedAt(), r.getPublishedBy());
      for (JsonNode entry : diff) {
        if (!"TEMPLATE".equals(entry.path("entityType").asText())) {
          continue;
        }
        put(out, entry.path("targetId").asText(null), ref);
        put(out, entry.path("resultId").asText(null), ref);
      }
    }
    byRelease = new Snapshot<>(out, Instant.now(), current);
    return out;
  }

  /** Template version id → when its row was created (seeded or published). */
  public Map<UUID, OffsetDateTime> createdByTemplate() {
    Snapshot<Map<UUID, OffsetDateTime>> held = created;
    if (held != null && Instant.now().isBefore(held.at().plus(TTL))) {
      return held.value();
    }
    Map<UUID, OffsetDateTime> out = new HashMap<>();
    for (BiBankParserTemplate t : templates.findAll()) {
      out.put(t.getId(), t.getCreatedAt());
    }
    created = new Snapshot<>(out, Instant.now(), 0);
    return out;
  }

  /**
   * Bank code → SMS from that bank's senders that phones could not read in the last {@value
   * #UNREAD_DAYS} days (the SMS coverage report, summed per bank). {@code null} while SMS format
   * discovery is switched off, because nothing is counted then.
   */
  public Map<String, Long> unreadByBank() {
    if (!properties.getFormatDiscovery().isEnabled()) {
      return null;
    }
    Snapshot<Map<String, Long>> held = unread;
    if (held != null && Instant.now().isBefore(held.at().plus(TTL))) {
      return held.value();
    }
    Map<String, Long> out = new HashMap<>();
    Map<String, String> bankBySender = coverage.bankBySender();
    LocalDate from = LocalDate.now(properties.zoneId()).minusDays(UNREAD_DAYS - 1L);
    for (BiUnmatchedSmsStatRepository.SenderTotals row : unmatched.totalsSince(from)) {
      String bank = bankBySender.get(row.getSenderId().toUpperCase(Locale.ROOT));
      if (bank != null && row.getMessages() != null) {
        out.merge(bank, row.getMessages(), Long::sum);
      }
    }
    unread = new Snapshot<>(out, Instant.now(), 0);
    return out;
  }

  /** Drop everything (after a publish). */
  public void evict() {
    byRelease = null;
    created = null;
    unread = null;
  }

  private static void put(Map<UUID, ReleaseRef> out, String id, ReleaseRef ref) {
    if (id == null || id.isBlank()) {
      return;
    }
    try {
      out.put(UUID.fromString(id), ref);
    } catch (IllegalArgumentException ignored) {
      // not an id; nothing to attach
    }
  }
}
