package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiMessageType;
import com.moipay.budgetinsights.repository.BiMessageTypeRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps categorisation layer 0 ({@code categorisation.message-type-categories}) in step with the
 * message type list's fixed categories, so the categoriser, the template preview and the
 * uncategorised report — which all read that map — follow what the Super Admin set.
 *
 * <p>The effective map is the configured map (application config or its defaults) with every type
 * on the list replaced by the list's value: a fixed category adds or overrides an entry, a type on
 * the list without one removes it. Types not on the list keep their configured entry. V1.15.0 seeds
 * the list with exactly the configured defaults, so nothing changes until someone edits the list.
 *
 * <p>Refreshed at start-up, after every change made on this instance, and every minute (so the
 * other instances follow). If the list cannot be read the configured map stays in force.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageTypeCategorySync {

  private final BiMessageTypeRepository messageTypes;
  private final InsightsProperties properties;

  /** The map as configured, captured before the first refresh replaces it. */
  private volatile Map<String, String> configured;

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    refresh();
  }

  @Scheduled(
      fixedDelayString = "${moipay.budget-insights.categorisation.message-type-refresh-ms:60000}",
      initialDelayString = "${moipay.budget-insights.categorisation.message-type-refresh-ms:60000}")
  public void scheduled() {
    refresh();
  }

  /** Recomputes the effective map from the list. Never throws. */
  public synchronized void refresh() {
    InsightsProperties.Categorisation categorisation = properties.getCategorisation();
    if (configured == null) {
      configured = normalised(categorisation.getMessageTypeCategories());
    }
    List<BiMessageType> rows;
    try {
      rows = messageTypes.findAll();
    } catch (RuntimeException ex) {
      log.warn("Insights message type list could not be read; categorisation keeps its current map: {}",
          ex.toString());
      return;
    }
    Map<String, String> effective = merge(configured, rows);
    if (!effective.equals(categorisation.getMessageTypeCategories())) {
      categorisation.setMessageTypeCategories(effective);
      log.info("Insights categorisation by message type now {}", effective);
    }
  }

  /** Configured entries, then the list's: listed types take the list's fixed category or none. */
  static Map<String, String> merge(Map<String, String> configured, List<BiMessageType> rows) {
    Map<String, String> out = new LinkedHashMap<>(configured == null ? Map.of() : configured);
    for (BiMessageType row : rows) {
      String code = row.getCode().trim().toUpperCase(Locale.ROOT);
      String category = row.getFixedCategoryCode();
      if (category == null || category.isBlank()) {
        out.remove(code);
      } else {
        out.put(code, category.trim());
      }
    }
    return out;
  }

  private static Map<String, String> normalised(Map<String, String> map) {
    Map<String, String> out = new LinkedHashMap<>();
    if (map != null) {
      map.forEach((k, v) -> {
        if (k != null) {
          out.put(k.trim().toUpperCase(Locale.ROOT), v);
        }
      });
    }
    return out;
  }
}
