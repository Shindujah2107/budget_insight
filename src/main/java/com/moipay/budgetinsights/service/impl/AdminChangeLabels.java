package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Plain-words labels and summaries for pending changes and release history. */
final class AdminChangeLabels {

  private static final Map<String, String> FIELD_NAMES = new LinkedHashMap<>();

  static {
    FIELD_NAMES.put("nameEn", "English name");
    FIELD_NAMES.put("nameSi", "Sinhala name");
    FIELD_NAMES.put("nameTa", "Tamil name");
    FIELD_NAMES.put("iconKey", "icon");
    FIELD_NAMES.put("colourHex", "colour");
    FIELD_NAMES.put("sortOrder", "order");
    FIELD_NAMES.put("active", "status");
    FIELD_NAMES.put("categoryId", "category");
    FIELD_NAMES.put("priority", "priority");
    FIELD_NAMES.put("matchType", "match type");
    FIELD_NAMES.put("pattern", "pattern");
    FIELD_NAMES.put("ruleSpec", "names");
    FIELD_NAMES.put("senderId", "sender id");
    FIELD_NAMES.put("countryCode", "country");
    FIELD_NAMES.put("fieldMap", "field map");
    FIELD_NAMES.put("builderSpec", "template definition");
    FIELD_NAMES.put("spendBucket", "bucket");
    FIELD_NAMES.put("bankName", "bank name");
    FIELD_NAMES.put("needsPct", "Needs %");
    FIELD_NAMES.put("wantsPct", "Wants %");
    FIELD_NAMES.put("savingsPct", "Savings %");
    FIELD_NAMES.put("trailingMonths", "recommendation period");
    FIELD_NAMES.put("defaultAlertPct", "default alert threshold");
    FIELD_NAMES.put("paceTolerancePct", "pace tolerance");
    FIELD_NAMES.put("alertCategoryApproaching", "category approaching alert");
    FIELD_NAMES.put("alertCategoryExceeded", "category exceeded alert");
    FIELD_NAMES.put("alertBudgetExceeded", "monthly budget exceeded alert");
  }

  /** Fields whose summary shows the values, e.g. "bucket Wants → Needs". */
  private static final Set<String> SHOW_VALUES =
      Set.of(
          "spendBucket",
          "bankName",
          "needsPct",
          "wantsPct",
          "savingsPct",
          "trailingMonths",
          "defaultAlertPct",
          "paceTolerancePct",
          "alertCategoryApproaching",
          "alertCategoryExceeded",
          "alertBudgetExceeded");

  private AdminChangeLabels() {}

  /** "Spending category · DINING_OUT", "Bank SMS sender · 7083 HNB", ... */
  static String label(ConfigEntityType entity, JsonNode state, AdminEffectiveView view) {
    if (state == null) {
      return entity.label();
    }
    String key =
        switch (entity) {
          case CATEGORY -> state.path("code").asText();
          case SENDER -> state.path("bankCode").asText() + " " + state.path("senderId").asText();
          case TEMPLATE -> state.path("bankCode").asText() + " " + state.path("messageType").asText();
          case MERCHANT_RULE -> rule(view.convert(state, AdminMerchantRuleState.class));
          case BANK -> state.path("bankCode").asText() + " " + state.path("bankName").asText();
          case BUDGET_POLICY -> "";
        };
    if (key.isBlank()) {
      return entity.label();
    }
    return entity.label() + " · " + key;
  }

  /** A merchant rule in a few words: its names when it has a form, otherwise its pattern. */
  static String rule(AdminMerchantRuleState r) {
    if (r == null) {
      return "";
    }
    if (r.ruleSpec() != null && r.ruleSpec().names() != null && !r.ruleSpec().names().isEmpty()) {
      return r.ruleSpec().matchType() + " " + String.join(", ", r.ruleSpec().names());
    }
    String p = r.pattern() == null ? "" : r.pattern();
    return r.matchType() + " " + (p.length() > 60 ? p.substring(0, 57) + "..." : p);
  }

  /** "New", "Deleted", "Turned off", or the fields that changed. */
  static String summary(ConfigChangeOp op, JsonNode before, JsonNode after) {
    return switch (op) {
      case CREATE -> "New";
      case DELETE -> "Deleted";
      case RETIRE -> "Retired";
      case DEACTIVATE -> "Turned off";
      case ACTIVATE -> "Turned on";
      case UPDATE -> changed(before, after);
    };
  }

  private static String changed(JsonNode before, JsonNode after) {
    if (before == null || after == null) {
      return "Changed";
    }
    List<String> fields = new ArrayList<>();
    Iterator<String> names = after.fieldNames();
    while (names.hasNext()) {
      String f = names.next();
      if ("templateVersion".equals(f)) {
        continue;
      }
      if (!Objects.equals(before.get(f), after.get(f))) {
        String name = FIELD_NAMES.getOrDefault(f, f);
        fields.add(
            SHOW_VALUES.contains(f)
                ? name + " " + value(f, before.get(f)) + " → " + value(f, after.get(f))
                : name);
      }
    }
    if (after.has("templateVersion")) {
      String v = "new version (was v" + before.path("templateVersion").asInt() + ")";
      return fields.isEmpty() ? v : v + ": " + String.join(", ", fields) + " changed";
    }
    if (fields.isEmpty()) {
      return "No visible change";
    }
    String joined = String.join(", ", fields);
    boolean valuesShown = fields.stream().anyMatch(x -> x.contains(" → "));
    return Character.toUpperCase(joined.charAt(0))
        + joined.substring(1)
        + (valuesShown ? "" : " changed");
  }

  private static String value(String field, JsonNode v) {
    if ("spendBucket".equals(field)) {
      if (v == null || v.isNull() || v.asText().isBlank()) {
        return "Not counted";
      }
      String t = v.asText();
      return t.charAt(0) + t.substring(1).toLowerCase(java.util.Locale.ROOT);
    }
    if (v == null || v.isNull()) {
      return "—";
    }
    if (v.isBoolean()) {
      return v.asBoolean() ? "on" : "off";
    }
    if (field.endsWith("Pct")) {
      return v.asText() + "%";
    }
    if ("trailingMonths".equals(field)) {
      return v.asText() + " months";
    }
    return v.asText();
  }
}
