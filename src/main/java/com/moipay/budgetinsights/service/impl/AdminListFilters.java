package com.moipay.budgetinsights.service.impl;

import java.util.Locale;

/** Shared admin list filters: {@code q} (text search) and {@code active} (true | false | all). */
final class AdminListFilters {

  private AdminListFilters() {}

  static boolean matchesActive(String active, boolean value) {
    if (active == null || active.isBlank() || "all".equalsIgnoreCase(active)) {
      return true;
    }
    return Boolean.parseBoolean(active) == value;
  }

  static boolean matchesText(String q, String... values) {
    if (q == null || q.isBlank()) {
      return true;
    }
    String needle = q.trim().toLowerCase(Locale.ROOT);
    for (String v : values) {
      if (v != null && v.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }
}
