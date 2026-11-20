package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Whether a customer is entitled to Premium-only features, decided on the server only.
 *
 * <p>There is no subscription, trial or grace-period record in MoneyReserve yet, and nothing the
 * app sends (an "isPremium" flag) is trusted. Until subscriptions exist, the only entitled
 * customers are those the operator lists in {@code moneyreserve.insights.premium.user-ids}, each
 * optionally with the last day it is valid ({@code <userId>@2026-12-31}); the list is empty by
 * default, so Premium-only features are closed to everyone. When subscriptions arrive, this is the
 * one class to change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PremiumEntitlements {

  private final InsightsProperties properties;

  /** Entitled on {@code today} (the app's calendar day). */
  public boolean hasPremium(UUID userId, LocalDate today) {
    if (userId == null) {
      return false;
    }
    for (String raw : properties.getPremium().getUserIds()) {
      if (raw == null || raw.isBlank()) {
        continue;
      }
      String entry = raw.trim();
      int at = entry.indexOf('@');
      String id = at < 0 ? entry : entry.substring(0, at);
      if (!id.equalsIgnoreCase(userId.toString())) {
        continue;
      }
      if (at < 0) {
        return true;
      }
      try {
        return !today.isAfter(LocalDate.parse(entry.substring(at + 1)));
      } catch (DateTimeParseException e) {
        log.warn("Premium entitlement entry has an unreadable end date; treated as not entitled");
        return false;
      }
    }
    return false;
  }
}
