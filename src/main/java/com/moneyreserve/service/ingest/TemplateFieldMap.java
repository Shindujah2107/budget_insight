package com.moneyreserve.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code field_map} of an SMS parser template: its schema, and the reference reading of it
 * (what a handset does with a match). The reading is the same one {@code BankSmsTemplateSeedTest}
 * checks the V1.3.0 seed against.
 */
public final class TemplateFieldMap {

  private static final Set<String> INSTRUMENTS = Set.of("CARD", "BANK");
  private static final Set<String> DIRECTIONS = Set.of("DEBIT", "CREDIT");
  private static final Set<String> TAIL_POLICIES = Set.of("ABSENT", "NOT_IN_SMS");

  /** Blocking problems and non-blocking warnings of one field map. */
  public record Check(List<String> errors, List<String> warnings) {
    public boolean ok() {
      return errors.isEmpty();
    }
  }

  private TemplateFieldMap() {}

  /**
   * @param groups the named groups of the template's pattern
   */
  public static Check check(JsonNode map, Set<String> groups) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (map == null || !map.isObject()) {
      errors.add("The field map must be a JSON object.");
      return new Check(errors, warnings);
    }
    Set<String> used = new LinkedHashSet<>();
    if (map.path("fieldMapVersion").asInt() != 1) {
      errors.add("fieldMapVersion must be 1.");
    }
    if (!INSTRUMENTS.contains(map.path("instrumentType").asText())) {
      errors.add("instrumentType must be CARD or BANK.");
    }
    if (map.path("logicalType").asText().isBlank()) {
      errors.add("logicalType is required.");
    }
    JsonNode senders = map.path("senders");
    if (!senders.isArray() || senders.isEmpty()) {
      errors.add("senders must list at least one bank SMS sender.");
    }
    requireGroup(map.path("amount"), "amount", groups, used, errors);

    JsonNode currency = map.path("currency");
    if (!currency.has("value")) {
      requireGroup(currency, "currency", groups, used, errors);
    }

    JsonNode dir = map.path("direction");
    if (dir.has("value")) {
      if (!DIRECTIONS.contains(dir.get("value").asText())) {
        errors.add("direction.value must be DEBIT or CREDIT.");
      }
    } else {
      requireGroup(dir, "direction", groups, used, errors);
      if (!dir.path("map").isObject() || dir.path("map").isEmpty()) {
        errors.add("direction.map must say which words mean DEBIT and CREDIT.");
      } else {
        dir.path("map")
            .forEach(
                v -> {
                  if (!DIRECTIONS.contains(v.asText())) {
                    errors.add("direction.map values must be DEBIT or CREDIT.");
                  }
                });
      }
    }

    JsonNode merchant = map.path("merchant");
    if (!merchant.isMissingNode() && !merchant.isNull()) {
      requireGroup(merchant, "merchant", groups, used, errors);
      if (merchant.has("fallbackGroup")) {
        String fb = merchant.get("fallbackGroup").asText();
        if (!groups.contains(fb)) {
          errors.add("merchant.fallbackGroup '" + fb + "' is not a group in the pattern.");
        }
        used.add(fb);
      }
    }

    JsonNode date = map.path("txnDate");
    if (date.has("source")) {
      if (!"SMS_RECEIVED_DATE".equals(date.get("source").asText())) {
        errors.add("txnDate.source can only be SMS_RECEIVED_DATE.");
      }
      if (groups.contains("date")) {
        warnings.add("The pattern captures a date but the field map uses the SMS received date.");
      }
    } else {
      requireGroup(date, "txnDate", groups, used, errors);
      requireFormat(date, "txnDate", errors);
    }

    JsonNode time = map.path("txnTime");
    if (time.isObject()) {
      requireGroup(time, "txnTime", groups, used, errors);
      requireFormat(time, "txnTime", errors);
    }

    JsonNode tail = map.path("maskedTail");
    if (tail.has("group")) {
      requireGroup(tail, "maskedTail", groups, used, errors);
      int digits = tail.path("digits").asInt();
      if (digits != 3 && digits != 4) {
        errors.add("maskedTail.digits must be 3 or 4: the service stores the last three or four digits.");
      }
    } else if (!TAIL_POLICIES.contains(tail.path("policy").asText())) {
      errors.add("maskedTail needs a group, or policy ABSENT / NOT_IN_SMS.");
    }

    // Any other object carrying a "group" (the seed's fee) counts as a use.
    map.fields()
        .forEachRemaining(
            e -> {
              if (e.getValue().isObject() && e.getValue().has("group")) {
                used.add(e.getValue().get("group").asText());
              }
            });
    for (String g : groups) {
      if (!used.contains(g)) {
        warnings.add("The pattern captures '" + g + "' but the field map never uses it.");
      }
    }
    return new Check(errors, warnings);
  }

  /**
   * The reference reading of a match: amount, currency, direction, merchant, txnDate and
   * maskedTail as a handset would send them. Returns {@code null} when the pattern does not match.
   *
   * @param received the SMS received date, used when the field map says so
   */
  public static Map<String, String> read(
      Pattern pattern, JsonNode map, String sms, LocalDate received) {
    Matcher m = pattern.matcher(sms);
    if (!m.find()) {
      return null;
    }
    Map<String, String> out = new LinkedHashMap<>();
    String amount = group(m, map.path("amount").path("group").asText());
    if (amount != null) {
      amount = amount.replace(",", "");
      if (amount.startsWith(".")) {
        amount = "0" + amount;
      }
      out.put("amount", new BigDecimal(amount).setScale(2).toPlainString());
    }
    JsonNode cur = map.path("currency");
    out.put(
        "currency",
        cur.has("value") ? cur.get("value").asText() : group(m, cur.path("group").asText()));
    JsonNode dir = map.path("direction");
    if (dir.has("value")) {
      out.put("direction", dir.get("value").asText());
    } else {
      String word = group(m, dir.path("group").asText());
      out.put(
          "direction",
          word == null
              ? null
              : dir.path("map")
                  .path(word.replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                  .asText(null));
    }
    JsonNode merchant = map.path("merchant");
    if (merchant.isObject()) {
      String v = group(m, merchant.path("group").asText());
      if ((v == null || v.isBlank()) && merchant.has("fallbackGroup")) {
        v = group(m, merchant.get("fallbackGroup").asText());
      }
      out.put("merchant", v == null ? null : v.trim().replaceAll("\\s+", " "));
    }
    JsonNode date = map.path("txnDate");
    if (date.has("source")) {
      out.put("txnDate", received == null ? null : received.toString());
    } else {
      String raw = group(m, date.path("group").asText());
      out.put(
          "txnDate",
          raw == null
              ? null
              : LocalDate.parse(
                      raw, DateTimeFormatter.ofPattern(date.path("format").asText(), Locale.ROOT))
                  .toString());
    }
    JsonNode time = map.path("txnTime");
    if (time.isObject()) {
      out.put("txnTime", group(m, time.path("group").asText()));
    }
    JsonNode tail = map.path("maskedTail");
    out.put("maskedTail", tail.has("group") ? group(m, tail.get("group").asText()) : null);
    return out;
  }

  private static String group(Matcher m, String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    try {
      return m.group(name);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static void requireGroup(
      JsonNode node, String field, Set<String> groups, Set<String> used, List<String> errors) {
    String g = node.path("group").asText("");
    if (g.isBlank()) {
      errors.add(field + " needs a group (or a fixed value where allowed).");
      return;
    }
    if (!groups.contains(g)) {
      errors.add(field + ".group '" + g + "' is not a group in the pattern.");
    }
    used.add(g);
  }

  private static void requireFormat(JsonNode node, String field, List<String> errors) {
    String f = node.path("format").asText("");
    if (f.isBlank()) {
      errors.add(field + ".format is required.");
      return;
    }
    try {
      DateTimeFormatter.ofPattern(f, Locale.ROOT);
    } catch (IllegalArgumentException ex) {
      errors.add(field + ".format '" + f + "' is not a valid date/time format.");
    }
  }
}
