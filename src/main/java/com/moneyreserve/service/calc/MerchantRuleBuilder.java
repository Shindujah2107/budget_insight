package com.moneyreserve.service.calc;

import com.moneyreserve.domain.MatchType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Turns the Admin's merchant-rule form (names + EXACT / PREFIX / REGEX + a priority level) into the
 * row {@code bi_merchant_category_map} already stores, and back.
 *
 * <p>Pure functions, no Spring: the same generation runs for the test endpoint, for a save and in
 * unit tests, so what the Admin tested is exactly what is stored. The categoriser does not change;
 * it keeps matching {@code merchant_pattern} by {@code match_type} with {@link MerchantRuleMatcher}.
 *
 * <table>
 *   <caption>Generation</caption>
 *   <tr><th>Admin picks</th><th>Names</th><th>Stored type</th><th>Stored pattern</th></tr>
 *   <tr><td>REGEX (contains)</td><td>any</td><td>TERMS</td><td>{@code A|B} (keywords, see {@link MerchantTerms})</td></tr>
 *   <tr><td>PREFIX</td><td>one</td><td>PREFIX</td><td>{@code A}</td></tr>
 *   <tr><td>PREFIX</td><td>several</td><td>REGEX</td><td>{@code ^(?:A|B)}</td></tr>
 *   <tr><td>EXACT</td><td>one</td><td>EXACT</td><td>{@code A}</td></tr>
 *   <tr><td>EXACT</td><td>several</td><td>REGEX</td><td>{@code ^(?:A|B)$}</td></tr>
 * </table>
 */
public final class MerchantRuleBuilder {

  public static final int MAX_NAMES = 60;
  public static final int MIN_NAME = 2;
  public static final int MAX_NAME = 64;
  public static final int MAX_TOTAL = 600;
  public static final int MAX_PATTERN = 1000;

  /** Priority bands: the engine checks the lowest number first. */
  public static final int HIGH_PRIORITY = 5;
  public static final int LOW_PRIORITY = 500;
  public static final int DEFAULT_NORMAL_PRIORITY = 150;

  public enum Level {
    HIGH,
    NORMAL,
    LOW
  }

  /** What is stored for a rule. */
  public record Generated(MatchType matchType, String pattern) {}

  /** A name the Admin typed, refused, with why (reported as E.021 {@code details}). */
  public record NameProblem(String field, String message) {}

  /** A stored rule read back as the simple form, when its pattern allows it. */
  public record Derived(String matchType, List<String> names) {}

  private static final Pattern NAME_CHARS = Pattern.compile("^[\\p{L}\\p{M}0-9 &.'\\-]+$");
  private static final Pattern REGEX_TERM =
      Pattern.compile("^(\\\\b)?((?:[\\p{L}\\p{M}0-9 &'\\-]|\\\\\\.)+?)(\\\\b)?$");
  private static final Pattern ANCHORED_ALTERNATION =
      Pattern.compile("^\\^\\(\\?:(.+)\\)(\\$?)$");

  private MerchantRuleBuilder() {}

  // --- names ---------------------------------------------------------------------------------------

  /** Upper-cases, trims, collapses spaces and drops duplicates and blanks, keeping order. */
  public static List<String> normalise(List<String> names) {
    Set<String> out = new LinkedHashSet<>();
    if (names != null) {
      for (String n : names) {
        String v = MerchantRuleMatcher.normalise(n);
        if (v != null) {
          out.add(v);
        }
      }
    }
    return new ArrayList<>(out);
  }

  /**
   * The name rules (E.021): 1–20 names; each 2–64 characters of letters, digits, space and {@code &
   * . ' -}; all names together at most 100 characters; for REGEX each at least 3 characters.
   *
   * @param names already {@link #normalise normalised}
   */
  public static List<NameProblem> nameProblems(String matchType, List<String> names) {
    List<NameProblem> out = new ArrayList<>();
    if (names == null || names.isEmpty()) {
      out.add(new NameProblem("names", "add at least one merchant name"));
      return out;
    }
    if (names.size() > MAX_NAMES) {
      out.add(new NameProblem("names", "at most " + MAX_NAMES + " names"));
    }
    boolean keywords = "REGEX".equals(matchType);
    int total = 0;
    for (int i = 0; i < names.size(); i++) {
      String n = names.get(i);
      String field = "names[" + i + "]";
      total += n.length();
      if (n.length() < MIN_NAME || n.length() > MAX_NAME) {
        out.add(new NameProblem(field, "must be " + MIN_NAME + "-" + MAX_NAME + " characters"));
      }
      if (keywords) {
        // 'Contains' names are keywords: WORD, PHRASE, STEM*, =EXACT, -EXCLUDE (MerchantTerms).
        MerchantTerms.problem(n).ifPresent(m -> out.add(new NameProblem(field, m)));
      } else if (!NAME_CHARS.matcher(n).matches()) {
        out.add(new NameProblem(field, "may contain only letters, digits, spaces and & . ' -"));
      }
    }
    if (keywords) {
      MerchantTerms.listProblem(names).ifPresent(m -> out.add(new NameProblem("names", m)));
    }
    if (total > MAX_TOTAL) {
      out.add(new NameProblem("names", "all names together may be at most " + MAX_TOTAL + " characters"));
    }
    return out;
  }

  // --- generation ----------------------------------------------------------------------------------

  /**
   * The stored type and pattern for the form input.
   *
   * @param names valid, normalised names
   */
  public static Generated generate(String matchType, List<String> names) {
    MatchType type = MatchType.valueOf(matchType);
    if (type == MatchType.REGEX) {
      // 'Contains' is stored for the keyword engine; the Admin keeps seeing "REGEX" (contains).
      return new Generated(MatchType.TERMS, MerchantTerms.join(names));
    }
    if (names.size() == 1) {
      return new Generated(type, names.get(0));
    }
    List<String> escaped = names.stream().map(MerchantRuleBuilder::escape).toList();
    String alternation = "^(?:" + String.join("|", escaped) + ")";
    return new Generated(MatchType.REGEX, type == MatchType.EXACT ? alternation + "$" : alternation);
  }

  /**
   * Reads a stored rule back as the simple form when its pattern is one this builder could have
   * generated (every seed rule written as {@code \bA\b|\bB\b}, and anything saved from the form).
   * Anything else is advanced-only.
   */
  public static Optional<Derived> derive(MatchType storedType, String pattern) {
    if (pattern == null || pattern.isBlank()) {
      return Optional.empty();
    }
    if (storedType == MatchType.TERMS) {
      List<String> names = MerchantTerms.split(pattern);
      return names.isEmpty() || !nameProblems("REGEX", names).isEmpty()
          ? Optional.empty()
          : Optional.of(new Derived("REGEX", names));
    }
    if (storedType == MatchType.EXACT || storedType == MatchType.PREFIX) {
      List<String> one = List.of(MerchantRuleMatcher.normalise(pattern));
      return nameProblems(storedType.name(), one).isEmpty()
          ? Optional.of(new Derived(storedType.name(), one))
          : Optional.empty();
    }
    Matcher anchored = ANCHORED_ALTERNATION.matcher(pattern);
    if (anchored.matches()) {
      List<String> names = new ArrayList<>();
      for (String term : anchored.group(1).split("\\|", -1)) {
        String name = unescape(term);
        if (name == null) {
          return Optional.empty();
        }
        names.add(name);
      }
      String type = anchored.group(2).isEmpty() ? "PREFIX" : "EXACT";
      return roundTrips(type, names, pattern);
    }
    List<String> names = new ArrayList<>();
    for (String term : pattern.split("\\|", -1)) {
      Matcher m = REGEX_TERM.matcher(term);
      if (!m.matches()) {
        return Optional.empty();
      }
      String name = unescape(m.group(2));
      if (name == null) {
        return Optional.empty();
      }
      names.add(name);
    }
    return roundTrips("REGEX", names, pattern);
  }

  private static Optional<Derived> roundTrips(String type, List<String> names, String pattern) {
    if (!nameProblems(type, names).isEmpty()) {
      return Optional.empty();
    }
    // Only a pattern this builder reproduces byte for byte opens in the simple form; otherwise
    // saving the form would silently change what the rule matches. An old 'contains' rule is
    // compared with the old \bA\b|\bB\b form; saving it from the form stores it as TERMS.
    String expected = "REGEX".equals(type) ? legacyContains(names) : generate(type, names).pattern();
    return expected.equals(pattern)
        ? Optional.of(new Derived(type, names))
        : Optional.empty();
  }

  /** A TERMS pattern in its stored form: keywords normalised, de-duplicated, joined without spaces. */
  public static String canonicalTerms(String pattern) {
    return MerchantTerms.join(normalise(MerchantTerms.split(pattern)));
  }

  /** The 'contains' pattern this builder wrote before TERMS existed: {@code \bA\b|\bB\b}. */
  private static String legacyContains(List<String> names) {
    List<String> terms = new ArrayList<>();
    for (String n : names) {
      terms.add(boundary(n.charAt(0)) + escape(n) + boundary(n.charAt(n.length() - 1)));
    }
    return String.join("|", terms);
  }

  // --- priority ------------------------------------------------------------------------------------

  /** High 1–9, Normal 10–199, Low 200–999. */
  public static Level level(int priority) {
    if (priority < 10) {
      return Level.HIGH;
    }
    return priority < 200 ? Level.NORMAL : Level.LOW;
  }

  /**
   * The number a new or re-levelled rule gets.
   *
   * @param categoryNormalBlock the lowest Normal-band priority already used by the category's rules,
   *     if it has any (seed blocks: Transport 10, Dining Out 20 ... Debt payments 130)
   */
  public static int priorityFor(Level level, Integer categoryNormalBlock) {
    return switch (level) {
      case HIGH -> HIGH_PRIORITY;
      case LOW -> LOW_PRIORITY;
      case NORMAL -> categoryNormalBlock != null ? categoryNormalBlock : DEFAULT_NORMAL_PRIORITY;
    };
  }

  /** "High (1-9)", "Normal (10-199)", "Low (200-999)". */
  public static String band(Level level) {
    return switch (level) {
      case HIGH -> "1-9";
      case NORMAL -> "10-199";
      case LOW -> "200-999";
    };
  }

  // --- advanced patterns ---------------------------------------------------------------------------

  /**
   * Problems with a raw pattern typed in Advanced settings (E.013). EXACT and PREFIX patterns are
   * plain text; REGEX patterns must compile and stay inside the subset every engine evaluates the
   * same way: no inline flags, lookbehind, possessive quantifiers or back-references.
   */
  public static List<String> patternProblems(String matchType, String pattern) {
    List<String> out = new ArrayList<>();
    if (pattern == null || pattern.isBlank()) {
      out.add("The pattern is empty.");
      return out;
    }
    if (pattern.length() > MAX_PATTERN) {
      out.add("The pattern is longer than " + MAX_PATTERN + " characters.");
    }
    if ("TERMS".equals(matchType)) {
      // The same rules as names typed in the simple form (count, length, keyword syntax).
      for (NameProblem p : nameProblems("REGEX", normalise(MerchantTerms.split(pattern)))) {
        out.add(p.field() + ": " + p.message() + ".");
      }
      return out;
    }
    if (!"REGEX".equals(matchType)) {
      return out;
    }
    if (pattern.contains("(?<=") || pattern.contains("(?<!")) {
      out.add("Lookbehind is not allowed.");
    }
    if (Pattern.compile("\\(\\?[a-zA-Z-]+[:)]").matcher(pattern).find()) {
      out.add("Inline flags such as (?i) are not allowed; matching is already case-insensitive.");
    }
    if (Pattern.compile("[*+?}]\\+").matcher(pattern).find()) {
      out.add("Possessive quantifiers are not allowed.");
    }
    if (Pattern.compile("\\\\[1-9]|\\\\k<").matcher(pattern).find()) {
      out.add("Back-references are not allowed.");
    }
    try {
      Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException ex) {
      out.add("The pattern does not compile: " + ex.getDescription() + ".");
    }
    return out;
  }

  // --- helpers -------------------------------------------------------------------------------------

  private static String boundary(char edge) {
    return Character.isLetterOrDigit(edge) ? "\\b" : "";
  }

  /** Names hold only letters, digits, space and {@code & . ' -}; of those only '.' is special. */
  private static String escape(String name) {
    return name.replace(".", "\\.");
  }

  private static String unescape(String term) {
    String name = term.replace("\\.", ".");
    return NAME_CHARS.matcher(name).matches() && !name.contains("\\")
        ? name.toUpperCase(Locale.ROOT)
        : null;
  }
}
