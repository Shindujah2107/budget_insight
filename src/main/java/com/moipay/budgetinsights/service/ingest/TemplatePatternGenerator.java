package com.moipay.budgetinsights.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builder spec → pattern + field map, deterministically (architecture doc §7.7.2).
 *
 * <p>A spec is the template's fixed bank wording and its field slots, never sample text:
 *
 * <pre>{@code
 * { "specVersion": 1,
 *   "segments": [ {"literal":"LKR "}, {"field":"AMOUNT"}, {"literal":" "},
 *                 {"field":"DIRECTION","words":{"debited":"DEBIT","credited":"CREDIT"}},
 *                 {"literal":" to Ac No:"}, {"ignore":"MASKED_ACCOUNT"}, {"literal":" on "},
 *                 {"field":"DATE","format":"dd/MM/yy"}, {"literal":" "}, {"field":"TIME","format":"HH:mm:ss"},
 *                 {"literal":" Reason:"}, {"field":"MERCHANT"}, {"literal":" Bal:"} ],
 *   "fixed":   { "currency":"LKR", "direction":null, "txnDateSource":null },
 *   "options": { "flexibleSpacing":false, "trailing":"MAY_CONTINUE", "generaliseNumbers":true },
 *   "logicalType":"ACCOUNT_TRANSACTION", "instrumentType":"BANK", "senders":["HNB"] }
 * }</pre>
 *
 * <p>Every field has one fixed, portable sub-pattern and group name — the shapes the V1.3.0 seed
 * uses — so the same spec always yields byte-identical output. The generator is the only place a
 * builder pattern is made: the preview (generate) and the save (regenerate-on-save) both call it.
 */
public final class TemplatePatternGenerator {

  public enum Field {
    AMOUNT,
    CURRENCY,
    DIRECTION,
    MERCHANT,
    DATE,
    TIME,
    TAIL
  }

  public enum IgnoreKind {
    MASKED_ACCOUNT("[0-9]+X+[0-9]+"),
    MASKED_X("[0-9]*X{2,}[0-9]*"),
    MASKED_STARS("[0-9]*\\*+[0-9]*"),
    NUMBER("[0-9]+"),
    AMOUNT("[0-9]*[0-9,]*(?:\\.[0-9]{1,2})?"),
    WORD("\\S+"),
    TEXT(".+?");

    final String regex;

    IgnoreKind(String regex) {
      this.regex = regex;
    }
  }

  /** Date formats the wizard offers; each maps to a fixed sub-pattern. */
  public static final List<String> DATE_FORMATS =
      List.of(
          "dd/MM/yy", "dd/MM/yyyy", "dd-MM-yy", "dd-MM-yyyy", "dd.MM.yy", "dd.MM.yyyy",
          "dd-MMM-yy", "dd-MMM-yyyy", "dd/MMM/yyyy", "yyyy-MM-dd", "yyyy/MM/dd");

  public static final List<String> TIME_FORMATS = List.of("HH:mm:ss", "HH:mm", "hh:mm:ss a", "hh:mm a");

  public static final String AMOUNT_REGEX = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2}";

  /** The result, or the problems that stop generation. */
  public record Result(String pattern, ObjectNode fieldMap, String templateView) {}

  /** A spec that cannot produce a template (E.020 / E.019). */
  public static final class SpecException extends RuntimeException {
    private final boolean literalPii;
    private final List<String> problems;

    SpecException(boolean literalPii, List<String> problems) {
      super(problems.get(0));
      this.literalPii = literalPii;
      this.problems = List.copyOf(problems);
    }

    /** True for E.019 (literal text holds a 4+ digit run or an e-mail), false for E.020. */
    public boolean literalPii() {
      return literalPii;
    }

    public List<String> problems() {
      return problems;
    }
  }

  private static final Pattern DIGIT_RUN_4 = Pattern.compile("[0-9]{4,}");
  private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}");
  private static final Pattern REGEX_SPECIAL = Pattern.compile("[\\\\.\\[\\]{}()*+?^$|]");

  private static final ObjectMapper JSON = new ObjectMapper();

  private TemplatePatternGenerator() {}

  /**
   * Generates the pattern and field map for a spec.
   *
   * @throws SpecException E.020 when a required field is missing, a field repeats, a format is
   *     unknown or the spec is malformed; E.019 when literal text would store a digit run of 4 or
   *     more or an e-mail address
   */
  public static Result generate(JsonNode spec) {
    List<String> problems = new ArrayList<>();
    List<String> pii = new ArrayList<>();
    if (spec == null || !spec.isObject()) {
      throw new SpecException(false, List.of("builderSpec must be a JSON object"));
    }
    if (spec.path("specVersion").asInt(0) != 1) {
      problems.add("specVersion must be 1");
    }
    JsonNode segments = spec.path("segments");
    if (!segments.isArray() || segments.isEmpty()) {
      throw new SpecException(false, List.of("the template has no segments"));
    }
    JsonNode options = spec.path("options");
    boolean flexible = options.path("flexibleSpacing").asBoolean(false);
    String trailing = options.path("trailing").asText("MAY_CONTINUE");
    if (!trailing.equals("MAY_CONTINUE") && !trailing.equals("ENDS")) {
      problems.add("options.trailing must be MAY_CONTINUE or ENDS");
    }
    JsonNode fixed = spec.path("fixed");

    StringBuilder regex = new StringBuilder("^");
    StringBuilder view = new StringBuilder();
    Set<Field> seen = EnumSet.noneOf(Field.class);
    Map<Field, JsonNode> fields = new LinkedHashMap<>();
    int last = lastFieldIndex(segments);
    for (int i = 0; i < segments.size(); i++) {
      JsonNode seg = segments.get(i);
      if (seg.has("literal")) {
        String lit = seg.get("literal").asText();
        if (lit.isEmpty()) {
          problems.add("segment " + i + ": literal text is empty");
          continue;
        }
        if (DIGIT_RUN_4.matcher(lit).find()) {
          pii.add("segment " + i + ": fixed text holds a number of 4 or more digits; mark it as a field or Ignore");
        }
        if (EMAIL.matcher(lit).find()) {
          pii.add("segment " + i + ": fixed text holds an e-mail address; mark it as Ignore");
        }
        regex.append(literal(lit, flexible));
        view.append(lit);
      } else if (seg.has("ignore")) {
        IgnoreKind kind = enumOf(IgnoreKind.class, seg.get("ignore").asText());
        if (kind == null) {
          problems.add("segment " + i + ": unknown ignore kind " + seg.get("ignore").asText());
          continue;
        }
        regex.append(kind == IgnoreKind.TEXT && i == segments.size() - 1 ? ".*" : kind.regex);
        view.append("{Ignore}");
      } else if (seg.has("field")) {
        Field field = enumOf(Field.class, seg.get("field").asText());
        if (field == null) {
          problems.add("segment " + i + ": unknown field " + seg.get("field").asText());
          continue;
        }
        if (!seen.add(field)) {
          problems.add(label(field) + " is marked more than once");
          continue;
        }
        fields.put(field, seg);
        String sub = fieldRegex(field, seg, i == last && i == segments.size() - 1, problems);
        regex.append(sub);
        view.append('{').append(label(field)).append('}');
      } else {
        problems.add("segment " + i + " must have literal, field or ignore");
      }
    }
    if ("ENDS".equals(trailing)) {
      regex.append('$');
    }

    // Required fields (E.020).
    if (!fields.containsKey(Field.AMOUNT)) {
      problems.add("Amount must be marked");
    }
    String fixedDirection = text(fixed, "direction");
    if (!fields.containsKey(Field.DIRECTION)
        && !("DEBIT".equals(fixedDirection) || "CREDIT".equals(fixedDirection))) {
      problems.add("Direction must be marked, or every message of this type fixed as Debit or Credit");
    }
    if (!fields.containsKey(Field.MERCHANT)) {
      problems.add("Merchant must be marked (for payment messages, mark the channel name)");
    }
    String dateSource = text(fixed, "txnDateSource");
    if (!fields.containsKey(Field.DATE) && dateSource != null && !"SMS_RECEIVED_DATE".equals(dateSource)) {
      problems.add("fixed.txnDateSource can only be SMS_RECEIVED_DATE");
    }
    List<String> senders = new ArrayList<>();
    spec.path("senders").forEach(s -> senders.add(s.asText()));
    if (senders.isEmpty()) {
      problems.add("Choose at least one sender for this template");
    }
    String instrument = spec.path("instrumentType").asText("");
    if (!instrument.equals("BANK") && !instrument.equals("CARD")) {
      problems.add("instrumentType must be BANK or CARD");
    }
    String logical = spec.path("logicalType").asText("");
    if (!logical.matches("^[A-Z][A-Z0-9_]{1,47}$")) {
      problems.add("logicalType must be UPPER_SNAKE, e.g. ACCOUNT_TRANSACTION");
    }
    String currency = text(fixed, "currency");
    if (!fields.containsKey(Field.CURRENCY) && currency != null && !currency.matches("^[A-Z]{3}$")) {
      problems.add("fixed.currency must be a 3-letter code");
    }

    if (!problems.isEmpty()) {
      throw new SpecException(false, problems);
    }
    if (!pii.isEmpty()) {
      throw new SpecException(true, pii);
    }

    ObjectNode map = JSON.createObjectNode();
    map.put("fieldMapVersion", 1);
    ArrayNode s = map.putArray("senders");
    senders.forEach(s::add);
    map.put("logicalType", logical);
    map.put("instrumentType", instrument);
    map.putObject("amount").put("group", "amount").put("normalise", "REMOVE_THOUSANDS_SEPARATOR_PAD_LEADING_ZERO");
    if (fields.containsKey(Field.CURRENCY)) {
      map.putObject("currency")
          .put("group", "currency")
          .put("onlyAccepted", "LKR")
          .put("otherwise", "SERVER_REJECTS_BY_DESIGN");
    } else {
      map.putObject("currency").put("value", currency == null ? "LKR" : currency);
    }
    if (fields.containsKey(Field.DIRECTION)) {
      ObjectNode dir = map.putObject("direction");
      dir.put("group", "dir").put("normalise", "LOWERCASE_REMOVE_WHITESPACE");
      ObjectNode m = dir.putObject("map");
      words(fields.get(Field.DIRECTION)).forEach((w, d) -> m.put(normaliseWord(w), d));
    } else {
      map.putObject("direction").put("value", fixedDirection);
    }
    map.putObject("merchant").put("group", "merchant").put("normalise", "TRIM_COLLAPSE_WHITESPACE");
    if (fields.containsKey(Field.DATE)) {
      map.putObject("txnDate")
          .put("group", "date")
          .put("format", fields.get(Field.DATE).path("format").asText())
          .put("zone", "Asia/Colombo");
    } else {
      map.putObject("txnDate").put("source", "SMS_RECEIVED_DATE").put("zone", "Asia/Colombo");
    }
    if (fields.containsKey(Field.TIME)) {
      map.putObject("txnTime").put("group", "time").put("format", fields.get(Field.TIME).path("format").asText());
    }
    if (fields.containsKey(Field.TAIL)) {
      map.putObject("maskedTail").put("group", "tail").put("digits", tailDigits(fields.get(Field.TAIL)));
    } else {
      map.putObject("maskedTail").put("policy", "ABSENT");
    }
    map.put("generatedBy", "ADMIN_TEMPLATE_BUILDER");
    return new Result(regex.toString(), map, view.toString());
  }

  /** The spec as the text the wizard shows for an existing template, e.g. {@code LKR {Amount} ...}. */
  public static String templateView(JsonNode spec) {
    if (spec == null || !spec.path("segments").isArray()) {
      return null;
    }
    StringBuilder view = new StringBuilder();
    for (JsonNode seg : spec.path("segments")) {
      if (seg.has("literal")) {
        view.append(seg.get("literal").asText());
      } else if (seg.has("ignore")) {
        view.append("{Ignore}");
      } else if (seg.has("field")) {
        Field f = enumOf(Field.class, seg.get("field").asText());
        view.append('{').append(f == null ? seg.get("field").asText() : label(f)).append('}');
      }
    }
    return view.toString();
  }

  // --- sub-patterns --------------------------------------------------------------------------------

  /**
   * How many digits the TAIL segment captures: its {@code digits} (3 or 4), or 4 for specs saved
   * before three-digit tails were allowed (V1.14.0), so existing templates keep their pattern.
   */
  static int tailDigits(JsonNode seg) {
    return seg != null && seg.has("digits") ? seg.get("digits").asInt() : 4;
  }

  private static String fieldRegex(Field field, JsonNode seg, boolean lastSegment, List<String> problems) {
    return switch (field) {
      case AMOUNT -> "(?<amount>" + AMOUNT_REGEX + ")";
      case CURRENCY -> "(?<currency>[A-Z]{3})";
      case MERCHANT -> lastSegment ? "(?<merchant>.+)" : "(?<merchant>.+?)";
      case TAIL -> {
        int digits = tailDigits(seg);
        if (digits != 3 && digits != 4) {
          problems.add("Account/Card must be the last 3 or 4 digits");
          yield "";
        }
        yield "[0-9]*(?<tail>[0-9]{" + digits + "})";
      }
      case DIRECTION -> {
        Map<String, String> words = words(seg);
        if (words.isEmpty()) {
          problems.add("Direction needs the word used for it, e.g. debited → Debit");
          yield "";
        }
        List<String> alts = new ArrayList<>();
        for (Map.Entry<String, String> e : words.entrySet()) {
          if (!"DEBIT".equals(e.getValue()) && !"CREDIT".equals(e.getValue())) {
            problems.add("Direction word '" + e.getKey() + "' must mean DEBIT or CREDIT");
          }
          if (!e.getKey().matches("^[A-Za-z][A-Za-z ]{0,29}$")) {
            problems.add("Direction words may only contain letters and spaces");
          }
          alts.add(escape(e.getKey()).replace(" ", " ?"));
        }
        yield "(?<dir>" + String.join("|", alts) + ")";
      }
      case DATE -> {
        String format = seg.path("format").asText("");
        if (!DATE_FORMATS.contains(format)) {
          problems.add("Date format '" + format + "' is not one the wizard supports");
          yield "";
        }
        yield "(?<date>" + dateRegex(format) + ")";
      }
      case TIME -> {
        String format = seg.path("format").asText("");
        if (!TIME_FORMATS.contains(format)) {
          problems.add("Time format '" + format + "' is not one the wizard supports");
          yield "";
        }
        yield "(?<time>" + timeRegex(format) + ")";
      }
    };
  }

  static String dateRegex(String format) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < format.length()) {
      char c = format.charAt(i);
      int n = 1;
      while (i + n < format.length() && format.charAt(i + n) == c) {
        n++;
      }
      switch (c) {
        case 'd', 'y' -> out.append("[0-9]{").append(n).append('}');
        case 'M' -> out.append(n >= 3 ? "[A-Za-z]{3}" : "[0-9]{" + n + "}");
        default -> out.append(escape(String.valueOf(c).repeat(n)));
      }
      i += n;
    }
    return out.toString();
  }

  static String timeRegex(String format) {
    return switch (format) {
      case "HH:mm:ss" -> "[0-9]{2}:[0-9]{2}:[0-9]{2}";
      case "HH:mm" -> "[0-9]{2}:[0-9]{2}";
      case "hh:mm:ss a" -> "[0-9]{2}:[0-9]{2}:[0-9]{2} [AP]M";
      default -> "[0-9]{2}:[0-9]{2} [AP]M";
    };
  }

  /** Literal bank wording, regex-escaped; a line break or (with flexible spacing) any space → \s+. */
  private static String literal(String text, boolean flexible) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        int j = i;
        boolean newline = false;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
          newline |= text.charAt(j) == '\n' || text.charAt(j) == '\r';
          j++;
        }
        if (flexible || newline) {
          out.append("\\s+");
        } else {
          out.append(j - i == 1 ? " " : " {" + (j - i) + "}");
        }
        i = j;
      } else {
        out.append(escape(String.valueOf(c)));
        i++;
      }
    }
    return out.toString();
  }

  static String escape(String s) {
    return REGEX_SPECIAL.matcher(s).replaceAll("\\\\$0");
  }

  private static Map<String, String> words(JsonNode seg) {
    Map<String, String> out = new LinkedHashMap<>();
    JsonNode w = seg.path("words");
    if (w.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> it = w.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        out.put(e.getKey(), e.getValue().asText());
      }
    }
    return out;
  }

  static String normaliseWord(String w) {
    return w.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }

  public static String label(Field f) {
    return switch (f) {
      case AMOUNT -> "Amount";
      case CURRENCY -> "Currency";
      case DIRECTION -> "Direction";
      case MERCHANT -> "Merchant";
      case DATE -> "Date";
      case TIME -> "Time";
      case TAIL -> "Account/Card";
    };
  }

  private static int lastFieldIndex(JsonNode segments) {
    int last = -1;
    for (int i = 0; i < segments.size(); i++) {
      if (segments.get(i).has("field")) {
        last = i;
      }
    }
    return last;
  }

  private static String text(JsonNode node, String key) {
    JsonNode v = node.path(key);
    return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
  }

  static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
    try {
      return Enum.valueOf(type, name);
    } catch (RuntimeException ex) {
      return null;
    }
  }
}
