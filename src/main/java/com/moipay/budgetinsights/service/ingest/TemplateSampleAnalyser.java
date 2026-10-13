package com.moipay.budgetinsights.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moipay.budgetinsights.service.ingest.TemplatePatternGenerator.Field;
import com.moipay.budgetinsights.service.ingest.TemplatePatternGenerator.IgnoreKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a pasted, redacted sample SMS for the template wizard (architecture doc §7.7.3–7.7.4).
 *
 * <p>{@link #analyse} tokenises it and <em>suggests</em> fields; the Admin confirms each one. {@link
 * #buildSpec} turns the confirmed highlights into a builder spec: the sample's fixed bank wording
 * plus field slots. Neither keeps the sample: both work on the string they are given and return
 * offsets, suggestions and a spec — never the sample text.
 */
public final class TemplateSampleAnalyser {

  public record Token(int start, int end, String kind) {}

  /**
   * @param direction DEBIT / CREDIT for a direction word
   * @param counterpartWords the word for the other direction, when it is a known pair
   * @param format the date or time format detected
   * @param ignoreKind for IGNORE suggestions
   */
  public record Suggestion(
      int start,
      int end,
      String field,
      double confidence,
      String direction,
      List<String> counterpartWords,
      String format,
      String ignoreKind) {}

  public record PiiWarning(String kind, int start, int end, String message) {}

  public record Analysis(int length, List<Token> tokens, List<Suggestion> suggestions, List<PiiWarning> piiWarnings) {}

  /**
   * One highlight the Admin confirmed.
   *
   * @param field AMOUNT, CURRENCY, DIRECTION, MERCHANT, DATE, TIME, TAIL or IGNORE
   */
  public record Annotation(
      int start,
      int end,
      String field,
      String direction,
      List<String> counterpartWords,
      String format,
      String ignoreKind) {}

  /** Highlights that cannot become a template (E.020). */
  public static final class AnnotationException extends RuntimeException {
    private final List<String> problems;

    AnnotationException(List<String> problems) {
      super(problems.get(0));
      this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
      return problems;
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Map<String, String> DIRECTION_WORDS =
      Map.ofEntries(
          Map.entry("debited", "DEBIT"),
          Map.entry("debit ed", "DEBIT"),
          Map.entry("debit", "DEBIT"),
          Map.entry("purchase", "DEBIT"),
          Map.entry("withdrawal", "DEBIT"),
          Map.entry("paid", "DEBIT"),
          Map.entry("transferred", "DEBIT"),
          Map.entry("credited", "CREDIT"),
          Map.entry("credit ed", "CREDIT"),
          Map.entry("credit", "CREDIT"),
          Map.entry("deposit", "CREDIT"),
          Map.entry("received", "CREDIT"),
          Map.entry("reversal", "CREDIT"));

  private static final Map<String, String> COUNTERPARTS =
      Map.of(
          "debited", "credited", "credited", "debited",
          "debit", "credit", "credit", "debit",
          "debit ed", "credit ed", "credit ed", "debit ed");

  private static final String DATE_ALT =
      "[0-9]{2}/[0-9]{2}/[0-9]{4}|[0-9]{2}/[0-9]{2}/[0-9]{2}|[0-9]{2}-[0-9]{2}-[0-9]{4}|[0-9]{2}-[0-9]{2}-[0-9]{2}"
          + "|[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}|[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}|[0-9]{2}-[A-Za-z]{3}-[0-9]{4}"
          + "|[0-9]{2}-[A-Za-z]{3}-[0-9]{2}|[0-9]{2}/[A-Za-z]{3}/[0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{4}/[0-9]{2}/[0-9]{2}";

  private static final Pattern TOKENS =
      Pattern.compile(
          "(?<masked>[0-9]+X+[0-9]+|[0-9]*\\*{2,}[0-9]*|X{3,}[0-9]*)"
              + "|(?<date>(?<![0-9])(?:" + DATE_ALT + ")(?![0-9]))"
              + "|(?<time>(?<![0-9])[0-9]{2}:[0-9]{2}(?::[0-9]{2})?(?: ?[AP]M)?(?![0-9]))"
              + "|(?<amount>(?<![0-9])(?<![0-9]\\.)(?:[0-9][0-9,]*\\.[0-9]{1,2}|\\.[0-9]{1,2})(?![0-9]))"
              + "|(?<number>[0-9]+)"
              + "|(?<word>[A-Za-z]+(?:'[A-Za-z]+)?)"
              + "|(?<symbol>\\S)");

  private static final Pattern CURRENCY_BEFORE = Pattern.compile("(?:LKR|Rs\\.?|[A-Z]{3})\\s*$");
  private static final Pattern CURRENCY_AFTER = Pattern.compile("^\\s*(?:LKR|[A-Z]{3})\\b");
  private static final Pattern BALANCE_BEFORE =
      Pattern.compile("(?i)(?:bal|balance|avl\\.? ?bal|av\\.bal|avail\\.? ?bal\\.?|available)[^0-9]{0,15}$");
  private static final Pattern TAIL_CONTEXT =
      Pattern.compile("(?i)(?:ending|#|a/c|ac no|card|account)[^0-9]{0,6}$");
  /** Stricter context for a bare 3-digit number: a lone "#" ("Txn #123") is not enough. */
  private static final Pattern TAIL3_CONTEXT =
      Pattern.compile("(?i)(?:ending|a/c|ac no|card|account)[^0-9]{0,6}$");
  private static final Pattern MERCHANT_MARKER =
      Pattern.compile("(?i)(?:purchase at |location: ?|reason: ?|remarks ?: ?|through |\\bat |\\bvia )");
  private static final Pattern MERCHANT_STOP =
      Pattern.compile("(?i)(?: on [0-9]| for (?:LKR|Rs)| has been|\\.? ?bal\\b|\\.? ?avl|,? ?amount| *, *[A-Z]{2,3}(?=,|\\s|$)|\\. |authorised| to your)");
  private static final Pattern LONG_DIGITS = Pattern.compile("[0-9]{9,}");
  private static final Pattern EMAIL = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}");
  private static final Pattern SALUTATION = Pattern.compile("(?i)\\bdear (?!customer|cardholder|sir|madam|valued)[A-Z][a-z]+");

  private TemplateSampleAnalyser() {}

  // --- analyse -------------------------------------------------------------------------------------

  public static Analysis analyse(String sample) {
    List<Token> tokens = new ArrayList<>();
    Matcher t = TOKENS.matcher(sample);
    while (t.find()) {
      String kind =
          t.group("masked") != null ? "MASKED"
              : t.group("date") != null ? "DATE"
              : t.group("time") != null ? "TIME"
              : t.group("amount") != null ? "AMOUNT"
              : t.group("number") != null ? "NUMBER"
              : t.group("word") != null ? "WORD"
              : "SYMBOL";
      tokens.add(new Token(t.start(), t.end(), kind));
    }

    // A 3-digit tail is only a fallback (V1.14.0): when the message has a 4-digit candidate, the
    // suggestions are exactly what they were before 3-digit tails were allowed.
    boolean fourDigitTail = false;
    for (Token tok : tokens) {
      String text = sample.substring(tok.start(), tok.end());
      String before = sample.substring(Math.max(0, tok.start() - 20), tok.start());
      if (("MASKED".equals(tok.kind()) && trailingDigits(text) == 4)
          || ("NUMBER".equals(tok.kind()) && text.length() >= 4 && TAIL_CONTEXT.matcher(before).find())) {
        fourDigitTail = true;
        break;
      }
    }

    List<Suggestion> out = new ArrayList<>();
    Set<String> used = new java.util.HashSet<>();
    for (Token tok : tokens) {
      String text = sample.substring(tok.start(), tok.end());
      String before = sample.substring(Math.max(0, tok.start() - 20), tok.start());
      switch (tok.kind()) {
        case "DATE" -> {
          if (used.add("DATE")) {
            out.add(new Suggestion(tok.start(), tok.end(), "DATE", 0.93, null, null, detectDateFormat(text), null));
          }
        }
        case "TIME" -> {
          if (used.add("TIME")) {
            out.add(new Suggestion(tok.start(), tok.end(), "TIME", 0.93, null, null, detectTimeFormat(text), null));
          }
        }
        case "MASKED" -> {
          int digitsAtEnd = trailingDigits(text);
          // A masked number ending in 3 or 4 visible digits: those digits are the tail.
          int tailLen = digitsAtEnd == 3 ? 3 : 4;
          if ((digitsAtEnd == 4 || (digitsAtEnd == 3 && !fourDigitTail)) && !used.contains("TAIL")) {
            used.add("TAIL");
            out.add(new Suggestion(tok.end() - tailLen, tok.end(), "TAIL", digitsAtEnd == 4 ? 0.85 : 0.8, null, null, null, null));
            if (tok.end() - tailLen > tok.start()) {
              out.add(new Suggestion(tok.start(), tok.end() - tailLen, "IGNORE", 0.85, null, null, null, ignoreKindOf(text.substring(0, text.length() - tailLen))));
            }
          } else {
            out.add(new Suggestion(tok.start(), tok.end(), "IGNORE", 0.9, null, null, null, ignoreKindOf(text)));
          }
        }
        case "AMOUNT" -> {
          String after = sample.substring(tok.end(), Math.min(sample.length(), tok.end() + 6));
          if (BALANCE_BEFORE.matcher(before).find()) {
            out.add(new Suggestion(tok.start(), tok.end(), "IGNORE", 0.95, null, null, null, IgnoreKind.AMOUNT.name()));
          } else if (!used.contains("AMOUNT")
              && (CURRENCY_BEFORE.matcher(before).find() || CURRENCY_AFTER.matcher(after).find())) {
            used.add("AMOUNT");
            out.add(new Suggestion(tok.start(), tok.end(), "AMOUNT", 0.97, null, null, null, null));
          } else {
            out.add(new Suggestion(tok.start(), tok.end(), "IGNORE", 0.6, null, null, null, IgnoreKind.AMOUNT.name()));
          }
        }
        case "NUMBER" -> {
          // A bare 3-digit number is a tail only where the account number ends: "280-2001****68"
          // starts with a branch code, it does not end in one.
          char next = tok.end() < sample.length() ? sample.charAt(tok.end()) : ' ';
          boolean endsNumber = Character.isWhitespace(next) || ".,;:)".indexOf(next) >= 0;
          boolean threeDigitTail =
              text.length() == 3 && endsNumber && !fourDigitTail && TAIL3_CONTEXT.matcher(before).find();
          if (((text.length() == 4 && TAIL_CONTEXT.matcher(before).find()) || threeDigitTail)
              && !used.contains("TAIL")) {
            used.add("TAIL");
            out.add(new Suggestion(tok.start(), tok.end(), "TAIL", 0.8, null, null, null, null));
          } else if (text.length() > 4 && TAIL_CONTEXT.matcher(before).find() && !used.contains("TAIL")) {
            used.add("TAIL");
            out.add(new Suggestion(tok.start(), tok.end() - 4, "IGNORE", 0.8, null, null, null, IgnoreKind.NUMBER.name()));
            out.add(new Suggestion(tok.end() - 4, tok.end(), "TAIL", 0.75, null, null, null, null));
          } else if (text.length() >= 3) {
            out.add(new Suggestion(tok.start(), tok.end(), "IGNORE", 0.7, null, null, null, IgnoreKind.NUMBER.name()));
          }
        }
        case "WORD" -> {
          String lower = text.toLowerCase(Locale.ROOT);
          String spaced = lower + (sample.startsWith(" ed", tok.end()) ? " ed" : "");
          String dir = DIRECTION_WORDS.get(spaced);
          if (dir != null && !used.contains("DIRECTION")) {
            used.add("DIRECTION");
            int end = spaced.length() > lower.length() ? tok.end() + 3 : tok.end();
            String counter = COUNTERPARTS.get(spaced);
            out.add(
                new Suggestion(
                    tok.start(), end, "DIRECTION", 0.95, dir,
                    counter == null ? List.of() : List.of(matchCase(counter, text)), null, null));
          }
        }
        default -> {}
      }
    }
    Suggestion merchant = merchant(sample, out);
    if (merchant == null) {
      // No "at" / "Reason:" marker: banks such as BOC name the transaction before the direction
      // word ("Online Transfer Debit Rs ..."), and that description is what categorises it.
      merchant =
          out.stream()
              .filter(x -> "DIRECTION".equals(x.field()) && x.start() > 1)
              .findFirst()
              .map(x -> new Suggestion(0, sample.substring(0, x.start()).stripTrailing().length(), "MERCHANT", 0.6, null, null, null, null))
              .orElse(null);
    }
    if (merchant != null) {
      out.add(merchant);
    }
    out.sort(Comparator.comparingInt(Suggestion::start));
    return new Analysis(sample.length(), tokens, out, pii(sample));
  }

  /** Text after "at", "Reason:", "Location:" … up to the next known wording or suggestion. */
  private static Suggestion merchant(String sample, List<Suggestion> taken) {
    Matcher m = MERCHANT_MARKER.matcher(sample);
    while (m.find()) {
      int start = m.end();
      while (start < sample.length() && sample.charAt(start) == ' ') {
        start++;
      }
      if (start >= sample.length() || !Character.isLetterOrDigit(sample.charAt(start)) && sample.charAt(start) != '*' && sample.charAt(start) != '"') {
        continue;
      }
      final int s0 = start;
      if (taken.stream().anyMatch(x -> x.start() <= s0 && s0 < x.end())) {
        continue;
      }
      int end = sample.length();
      Matcher stop = MERCHANT_STOP.matcher(sample);
      if (stop.find(start)) {
        end = stop.start();
      }
      for (Suggestion x : taken) {
        if (x.start() > start && x.start() < end && !"IGNORE".equals(x.field())) {
          end = x.start();
        }
      }
      int nl = sample.indexOf('\n', start);
      if (nl > 0 && nl < end) {
        end = nl;
      }
      while (end > start && (sample.charAt(end - 1) == ' ' || sample.charAt(end - 1) == ',' || sample.charAt(end - 1) == '.')) {
        end--;
      }
      if (end - start >= 2) {
        return new Suggestion(start, end, "MERCHANT", 0.8, null, null, null, null);
      }
    }
    return null;
  }

  static List<PiiWarning> pii(String sample) {
    List<PiiWarning> out = new ArrayList<>();
    Matcher d = LONG_DIGITS.matcher(sample);
    while (d.find()) {
      out.add(new PiiWarning("LONG_NUMBER", d.start(), d.end(), "A number of 9 or more digits may be a full account or card number. Replace it with X or synthetic digits."));
    }
    Matcher e = EMAIL.matcher(sample);
    while (e.find()) {
      out.add(new PiiWarning("EMAIL", e.start(), e.end(), "Remove the e-mail address."));
    }
    Matcher s = SALUTATION.matcher(sample);
    while (s.find()) {
      out.add(new PiiWarning("NAME", s.start(), s.end(), "This looks like a customer's name. Replace it with [NAME]."));
    }
    return out;
  }

  // --- highlights → spec ---------------------------------------------------------------------------

  /**
   * Turns confirmed highlights into a builder spec. Un-highlighted text becomes the template's fixed
   * wording; with {@code generaliseNumbers} every digit run in it becomes "any number", so no
   * reference number is frozen into the template.
   */
  public static ObjectNode buildSpec(
      String sample,
      List<Annotation> annotations,
      JsonNode fixed,
      JsonNode options,
      String logicalType,
      String instrumentType,
      List<String> senders) {
    List<String> problems = new ArrayList<>();
    List<Annotation> sorted = new ArrayList<>(annotations == null ? List.of() : annotations);
    sorted.sort(Comparator.comparingInt(Annotation::start));
    Set<Field> seen = EnumSet.noneOf(Field.class);
    int cursor = 0;
    boolean generalise = options == null || options.path("generaliseNumbers").asBoolean(true);
    ArrayNode segments = JSON.createArrayNode();
    for (Annotation a : sorted) {
      if (a.start() < 0 || a.end() > sample.length() || a.start() >= a.end()) {
        problems.add("A highlight is outside the sample");
        continue;
      }
      if (a.start() < cursor) {
        problems.add("Highlights overlap at character " + a.start());
        continue;
      }
      String text = sample.substring(a.start(), a.end());
      if (a.start() > cursor) {
        addLiteral(segments, sample.substring(cursor, a.start()), generalise);
      }
      cursor = a.end();
      if ("IGNORE".equals(a.field())) {
        String kind = a.ignoreKind() != null ? a.ignoreKind() : ignoreKindOf(text);
        if (TemplatePatternGenerator.enumOf(IgnoreKind.class, kind) == null) {
          problems.add("Unknown ignore kind " + kind);
          continue;
        }
        segments.addObject().put("ignore", kind);
        continue;
      }
      Field field = TemplatePatternGenerator.enumOf(Field.class, a.field());
      if (field == null) {
        problems.add("Unknown field " + a.field());
        continue;
      }
      if (!seen.add(field)) {
        problems.add(TemplatePatternGenerator.label(field) + " is highlighted more than once");
        continue;
      }
      ObjectNode seg = segments.addObject().put("field", field.name());
      switch (field) {
        case TAIL -> {
          if (!text.matches("^[0-9]{3,4}$")) {
            problems.add("Account/Card must be the last 3 or 4 digits; fewer visible digits cannot be captured (mark them Ignore)");
          } else {
            seg.put("digits", text.length());
          }
        }
        case DATE -> {
          String format = a.format() != null ? a.format() : detectDateFormat(text);
          if (format == null || !TemplatePatternGenerator.DATE_FORMATS.contains(format)) {
            problems.add("Choose the date format for '" + mask(text) + "'");
          } else if (!text.matches(TemplatePatternGenerator.dateRegex(format))) {
            problems.add("The highlighted date does not look like " + format);
          }
          seg.put("format", format);
        }
        case TIME -> {
          String format = a.format() != null ? a.format() : detectTimeFormat(text);
          if (format == null || !TemplatePatternGenerator.TIME_FORMATS.contains(format)) {
            problems.add("Choose the time format");
          }
          seg.put("format", format);
        }
        case AMOUNT -> {
          if (!text.matches(TemplatePatternGenerator.AMOUNT_REGEX)) {
            problems.add("The highlighted amount is not a number such as 2,450.00");
          }
        }
        case DIRECTION -> {
          String dir = a.direction() != null ? a.direction() : DIRECTION_WORDS.get(text.toLowerCase(Locale.ROOT));
          if (!"DEBIT".equals(dir) && !"CREDIT".equals(dir)) {
            problems.add("Say whether '" + text + "' means Debit or Credit");
            dir = "DEBIT";
          }
          ObjectNode words = seg.putObject("words");
          words.put(text, dir);
          String other = "DEBIT".equals(dir) ? "CREDIT" : "DEBIT";
          if (a.counterpartWords() != null) {
            for (String w : a.counterpartWords()) {
              if (w != null && !w.isBlank()) {
                words.put(w.trim(), other);
              }
            }
          }
        }
        default -> {}
      }
    }
    if (cursor < sample.length()) {
      String tail = sample.substring(cursor);
      boolean ends = options != null && "ENDS".equals(options.path("trailing").asText());
      if (ends) {
        addLiteral(segments, tail, generalise);
      } else {
        // Keep only the fixed wording up to the first number: what follows (balances, links,
        // sign-offs) varies and the pattern need not match it.
        Matcher firstDigit = Pattern.compile("[0-9]").matcher(tail);
        String keep = firstDigit.find() ? tail.substring(0, firstDigit.start()) : tail;
        int cut = Math.max(keep.lastIndexOf(' '), 0);
        keep = keep.length() > 24 ? keep.substring(0, cut) : keep;
        if (!keep.isEmpty()) {
          addLiteral(segments, keep, generalise);
        }
      }
    }
    if (!problems.isEmpty()) {
      throw new AnnotationException(problems);
    }
    ObjectNode spec = JSON.createObjectNode();
    spec.put("specVersion", 1);
    spec.set("segments", merge(segments));
    ObjectNode f = spec.putObject("fixed");
    f.put("currency", fixed == null || fixed.path("currency").isNull() || fixed.path("currency").asText().isBlank() ? "LKR" : fixed.path("currency").asText());
    f.set("direction", fixed == null ? null : nullIfBlank(fixed.path("direction")));
    f.set("txnDateSource", fixed == null ? null : nullIfBlank(fixed.path("txnDateSource")));
    ObjectNode o = spec.putObject("options");
    o.put("flexibleSpacing", options != null && options.path("flexibleSpacing").asBoolean(false));
    o.put("trailing", options != null && "ENDS".equals(options.path("trailing").asText()) ? "ENDS" : "MAY_CONTINUE");
    o.put("generaliseNumbers", generalise);
    spec.put("logicalType", logicalType);
    spec.put("instrumentType", instrumentType);
    ArrayNode s = spec.putArray("senders");
    if (senders != null) {
      senders.forEach(s::add);
    }
    return spec;
  }

  private static void addLiteral(ArrayNode segments, String text, boolean generalise) {
    if (!generalise) {
      segments.addObject().put("literal", text);
      return;
    }
    Matcher d = Pattern.compile("[0-9]+").matcher(text);
    int at = 0;
    while (d.find()) {
      if (d.start() > at) {
        segments.addObject().put("literal", text.substring(at, d.start()));
      }
      segments.addObject().put("ignore", IgnoreKind.NUMBER.name());
      at = d.end();
    }
    if (at < text.length()) {
      segments.addObject().put("literal", text.substring(at));
    }
  }

  private static ArrayNode merge(ArrayNode in) {
    ArrayNode out = JSON.createArrayNode();
    for (JsonNode seg : in) {
      JsonNode prev = out.isEmpty() ? null : out.get(out.size() - 1);
      if (seg.has("literal") && prev != null && prev.has("literal")) {
        ((ObjectNode) prev).put("literal", prev.get("literal").asText() + seg.get("literal").asText());
      } else {
        out.add(seg);
      }
    }
    return out;
  }

  // --- detection helpers ---------------------------------------------------------------------------

  public static String detectDateFormat(String text) {
    String[][] shapes = {
      {"[0-9]{2}/[0-9]{2}/[0-9]{4}", "dd/MM/yyyy"}, {"[0-9]{2}/[0-9]{2}/[0-9]{2}", "dd/MM/yy"},
      {"[0-9]{2}-[0-9]{2}-[0-9]{4}", "dd-MM-yyyy"}, {"[0-9]{2}-[0-9]{2}-[0-9]{2}", "dd-MM-yy"},
      {"[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}", "dd.MM.yyyy"}, {"[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}", "dd.MM.yy"},
      {"[0-9]{2}-[A-Za-z]{3}-[0-9]{4}", "dd-MMM-yyyy"}, {"[0-9]{2}-[A-Za-z]{3}-[0-9]{2}", "dd-MMM-yy"},
      {"[0-9]{2}/[A-Za-z]{3}/[0-9]{4}", "dd/MMM/yyyy"}, {"[0-9]{4}-[0-9]{2}-[0-9]{2}", "yyyy-MM-dd"},
      {"[0-9]{4}/[0-9]{2}/[0-9]{2}", "yyyy/MM/dd"}
    };
    for (String[] s : shapes) {
      if (text.matches(s[0])) {
        return s[1];
      }
    }
    return null;
  }

  public static String detectTimeFormat(String text) {
    if (text.matches("[0-9]{2}:[0-9]{2}:[0-9]{2} [AP]M")) {
      return "hh:mm:ss a";
    }
    if (text.matches("[0-9]{2}:[0-9]{2} [AP]M")) {
      return "hh:mm a";
    }
    if (text.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}")) {
      return "HH:mm:ss";
    }
    return text.matches("[0-9]{2}:[0-9]{2}") ? "HH:mm" : null;
  }

  static String ignoreKindOf(String text) {
    if (text.matches("[0-9]+X+[0-9]+")) {
      return IgnoreKind.MASKED_ACCOUNT.name();
    }
    if (text.matches("[0-9]*X{2,}[0-9]*")) {
      return IgnoreKind.MASKED_X.name();
    }
    if (text.matches("[0-9]*\\*+[0-9]*")) {
      return IgnoreKind.MASKED_STARS.name();
    }
    if (text.matches("[0-9]+")) {
      return IgnoreKind.NUMBER.name();
    }
    if (text.matches("[0-9,]*\\.?[0-9]+")) {
      return IgnoreKind.AMOUNT.name();
    }
    return text.matches("\\S+") ? IgnoreKind.WORD.name() : IgnoreKind.TEXT.name();
  }

  private static int trailingDigits(String text) {
    int n = 0;
    for (int i = text.length() - 1; i >= 0 && Character.isDigit(text.charAt(i)); i--) {
      n++;
    }
    return n;
  }

  private static String matchCase(String word, String like) {
    if (like.equals(like.toUpperCase(Locale.ROOT))) {
      return word.toUpperCase(Locale.ROOT);
    }
    if (Character.isUpperCase(like.charAt(0))) {
      return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
    return word;
  }

  private static JsonNode nullIfBlank(JsonNode v) {
    return v == null || v.isMissingNode() || v.isNull() || v.asText().isBlank()
        ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
        : v;
  }

  /** Digits replaced, so an error message never repeats a value from the sample. */
  private static String mask(String text) {
    return text.replaceAll("[0-9]", "9");
  }
}
