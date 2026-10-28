package com.moneyreserve.service.calc;

import com.moneyreserve.domain.MatchType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;

/**
 * How merchant rules match a merchant string and which matching rule wins — the single definition
 * shared by the categoriser, the admin's "Test this rule" and the usage counts, so a test result is
 * exactly what ingest will do.
 *
 * <p><b>Matching.</b> EXACT compares the whole normalised string, PREFIX its start, REGEX is a
 * case-insensitive {@code find()} anywhere (all three unchanged). TERMS is the keyword engine
 * ({@link MerchantTerms}) over the cleaned merchant words ({@link MerchantText}).
 *
 * <p><b>Choosing.</b> Every rule is tried and the winner is, in order: the priority <i>level</i>
 * (High 1–9, Normal 10–199, Low 200+; an admin override always wins), then the most <i>specific</i>
 * match (an exact name, then more words, then a single word, then a stem), then the lower priority
 * number, then EXACT before PREFIX before REGEX before TERMS. Specificity is what lets {@code DRUG
 * STORE} (Health Care) beat {@code STORE} (Shopping) without reordering categories.
 */
@Slf4j
public class MerchantRuleMatcher {

  /** Specificity of a match by an old-style (EXACT / PREFIX / REGEX) rule. */
  public static final int LEGACY_SPECIFICITY = MerchantTerms.WORD;

  /** A pattern that can never match: cached in place of one that does not compile. */
  private static final Pattern NEVER = Pattern.compile("(?!)");

  /** Bound on each cache: the admin screens test arbitrary text, so the caches must not grow forever. */
  static final int MAX_CACHED = 20_000;

  private final Map<String, Pattern> compiled = new ConcurrentHashMap<>();
  private final Map<String, MerchantTerms> terms = new ConcurrentHashMap<>();
  private final Map<String, List<String>> tokens = new ConcurrentHashMap<>();

  /** A rule that matched, with how specifically. */
  public record Match<R>(R rule, int specificity, String matchedText) {}

  /** The static evaluation order (priority, then type) — the order rules are listed and tried. */
  public static <R> Comparator<R> order(Function<R, Integer> priority, Function<R, MatchType> type) {
    return Comparator.comparing((R r) -> priority.apply(r) == null ? 100 : priority.apply(r))
        .thenComparing(r -> type.apply(r) == null ? MatchType.REGEX : type.apply(r));
  }

  /** Priority level band: 0 High (1–9), 1 Normal (10–199), 2 Low (200+). */
  public static int band(Integer priority) {
    int p = priority == null ? 100 : priority;
    return p < 10 ? 0 : (p < 200 ? 1 : 2);
  }

  /** How matches are ranked: level, specificity (higher first), priority, type. */
  public static <R> Comparator<Match<R>> ranking(
      Function<R, Integer> priority, Function<R, MatchType> type) {
    return Comparator.comparing((Match<R> m) -> band(priority.apply(m.rule())))
        .thenComparing(Comparator.comparing((Match<R> m) -> m.specificity()).reversed())
        .thenComparing(Match::rule, order(priority, type));
  }

  /**
   * Every rule that matches {@code merchant}, best first. The caller walks the list and takes the
   * first whose category it can use.
   */
  public <R> List<Match<R>> ranked(
      List<R> rules,
      Function<R, Integer> priority,
      Function<R, MatchType> type,
      Function<R, String> pattern,
      String merchant,
      Comparator<R> tieBreak) {
    String needle = normalise(merchant);
    List<Match<R>> out = new ArrayList<>();
    if (needle == null) {
      return out;
    }
    List<String> words = tokensOf(needle);
    for (R r : rules) {
      Optional<Match<R>> m = match(r, type.apply(r), pattern.apply(r), needle, words);
      m.ifPresent(out::add);
    }
    Comparator<Match<R>> rank = ranking(priority, type);
    if (tieBreak != null) {
      rank = rank.thenComparing(Match::rule, tieBreak);
    }
    out.sort(rank);
    return out;
  }

  /** The best matching rule, if any. */
  public <R> Optional<R> firstMatch(
      List<R> rules,
      Function<R, Integer> priority,
      Function<R, MatchType> type,
      Function<R, String> pattern,
      String merchant) {
    List<Match<R>> all = ranked(rules, priority, type, pattern, merchant, null);
    return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0).rule());
  }

  /**
   * @param needle an already {@link #normalise normalised} merchant string
   */
  public boolean matches(MatchType type, String pattern, String needle) {
    return specificity(type, pattern, needle).isPresent();
  }

  /**
   * How specifically a rule matches, or empty when it does not.
   *
   * @param needle an already {@link #normalise normalised} merchant string
   */
  public OptionalInt specificity(MatchType type, String pattern, String needle) {
    if (needle == null) {
      return OptionalInt.empty();
    }
    return match(null, type, pattern, needle, tokensOf(needle))
        .map(m -> OptionalInt.of(m.specificity()))
        .orElse(OptionalInt.empty());
  }

  /** The text that made a rule match (a keyword for TERMS), for explaining a result. */
  public Optional<String> matchedText(MatchType type, String pattern, String needle) {
    if (needle == null) {
      return Optional.empty();
    }
    return match(null, type, pattern, needle, tokensOf(needle)).map(Match::matchedText);
  }

  private <R> Optional<Match<R>> match(
      R rule, MatchType type, String pattern, String needle, List<String> tokens) {
    if (type == null || pattern == null || pattern.isBlank() || needle == null) {
      return Optional.empty();
    }
    switch (type) {
      case EXACT:
        return needle.equals(normalise(pattern))
            ? Optional.of(new Match<>(rule, MerchantTerms.EXACT + tokens.size(), needle))
            : Optional.empty();
      case PREFIX:
        String prefix = normalise(pattern);
        return prefix != null && needle.startsWith(prefix)
            ? Optional.of(new Match<>(rule, LEGACY_SPECIFICITY, prefix))
            : Optional.empty();
      case REGEX:
        Pattern p = compile(pattern);
        if (p == null) {
          return Optional.empty();
        }
        Matcher m = p.matcher(needle);
        return m.find()
            ? Optional.of(new Match<>(rule, LEGACY_SPECIFICITY, m.group()))
            : Optional.empty();
      case TERMS:
        bound(terms);
        return terms
            .computeIfAbsent(pattern, MerchantTerms::parse)
            .match(tokens)
            .map(h -> new Match<>(rule, h.specificity(), h.keyword()));
      default:
        return Optional.empty();
    }
  }

  /** Forgets compiled patterns (after reference data changed). */
  public void clear() {
    compiled.clear();
    terms.clear();
    tokens.clear();
  }

  /** The cleaned words of a merchant, computed once (the admin screens test every rule per merchant). */
  private List<String> tokensOf(String needle) {
    bound(tokens);
    return tokens.computeIfAbsent(needle, n -> List.copyOf(MerchantText.tokens(n)));
  }

  private static void bound(Map<String, ?> cache) {
    if (cache.size() > MAX_CACHED) {
      cache.clear();
    }
  }

  /**
   * Upper-cases, trims and collapses internal whitespace. Bank messages are inconsistent about
   * spacing and case for the same merchant, so both sides of every comparison go through this.
   * TERMS rules additionally clean the result into words ({@link MerchantText#tokens}).
   */
  public static String normalise(String raw) {
    if (raw == null) {
      return null;
    }
    String v = raw.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    return v.isEmpty() ? null : v;
  }

  private Pattern compile(String pattern) {
    bound(compiled);
    Pattern p =
        compiled.computeIfAbsent(
            pattern,
            raw -> {
              try {
                return Pattern.compile(raw, Pattern.CASE_INSENSITIVE);
              } catch (PatternSyntaxException ex) {
                // A bad pattern must not take categorisation down with it: the rule is dropped and
                // the defect logged once.
                log.error("Invalid Insights merchant regex ignored: {}", raw, ex);
                return NEVER;
              }
            });
    return p == NEVER ? null : p;
  }
}
