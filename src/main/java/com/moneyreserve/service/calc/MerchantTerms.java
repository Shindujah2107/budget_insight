package com.moneyreserve.service.calc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The generic keyword engine behind {@link com.moneyreserve.domain.MatchType#TERMS}
 * (layers 2–4 of categorisation).
 *
 * <p>A rule is a list of keywords. How a keyword is written decides how it matches the merchant's
 * cleaned words ({@link MerchantText#tokens}):
 *
 * <table>
 *   <caption>Keyword kinds</caption>
 *   <tr><th>Written</th><th>Kind</th><th>Matches</th><th>Specificity</th></tr>
 *   <tr><td>{@code PHARMACY}</td><td>word</td><td>that whole word</td><td>10</td></tr>
 *   <tr><td>{@code FILLING STATION}</td><td>phrase</td><td>those words in sequence, or joined
 *       ({@code FILLINGSTATION})</td><td>10 per word</td></tr>
 *   <tr><td>{@code GROCER*}</td><td>stem</td><td>any word starting with it</td><td>10 per word − 5</td></tr>
 *   <tr><td>{@code =LAB}</td><td>exact</td><td>the whole cleaned merchant</td><td>1000 + words</td></tr>
 *   <tr><td>{@code -SURCHARGE}</td><td>exclusion</td><td>if it matches, the rule does not</td><td>—</td></tr>
 * </table>
 *
 * <p>A rule's specificity for a merchant is that of its most specific matching keyword. Across
 * rules the categoriser prefers priority level, then specificity, then priority number ({@link
 * MerchantRuleMatcher#ranking}), so {@code DRUG STORE} (two words) beats {@code STORE} (one word)
 * without reordering categories.
 *
 * <p>Stored as the keywords joined by {@value #SEPARATOR} in {@code merchant_pattern}.
 */
public final class MerchantTerms {

  public static final String SEPARATOR = "|";
  public static final int WORD = 10;
  public static final int STEM_PENALTY = 5;
  public static final int EXACT = 1000;

  /** Shortest word a keyword may consist of (channel codes such as {@code MB} are two letters). */
  public static final int MIN_WORD = 2;

  /** Shortest stem: {@code GR*} would match half the dictionary. */
  public static final int MIN_STEM = 3;

  private static final Pattern KEYWORD_CHARS = Pattern.compile("^[-=]?[\\p{L}\\p{M}0-9 &.'\\-/*]+$");

  /** One parsed keyword. */
  record Term(String text, boolean exclude, boolean exact, List<String> words, List<Boolean> stems) {

    int specificity(List<String> tokens) {
      int n = words.size();
      if (exact) {
        return words.equals(tokens) ? EXACT + n : -1;
      }
      boolean anyStem = stems.contains(Boolean.TRUE);
      for (int i = 0; i + n <= tokens.size(); i++) {
        boolean all = true;
        for (int j = 0; j < n && all; j++) {
          String t = tokens.get(i + j);
          all = stems.get(j) ? t.startsWith(words.get(j)) : t.equals(words.get(j));
        }
        if (all) {
          return n * WORD - (anyStem ? STEM_PENALTY : 0);
        }
      }
      if (n > 1 && !anyStem && tokens.contains(String.join("", words))) {
        return n * WORD;
      }
      return -1;
    }
  }

  /** The keyword of a rule that decided a match, and how specific it was. */
  public record Hit(String keyword, int specificity) {}

  private final List<Term> include;
  private final List<Term> exclude;

  private MerchantTerms(List<Term> include, List<Term> exclude) {
    this.include = include;
    this.exclude = exclude;
  }

  /** Parses a stored pattern ({@code KEELLS|FOOD CITY|GROCER*|-SURCHARGE}). Invalid keywords are skipped. */
  public static MerchantTerms parse(String pattern) {
    List<Term> in = new ArrayList<>();
    List<Term> ex = new ArrayList<>();
    if (pattern != null) {
      for (String raw : pattern.split(Pattern.quote(SEPARATOR))) {
        Optional<Term> t = term(raw);
        t.ifPresent(term -> (term.exclude() ? ex : in).add(term));
      }
    }
    return new MerchantTerms(List.copyOf(in), List.copyOf(ex));
  }

  /** Joins keywords into the stored pattern. */
  public static String join(List<String> keywords) {
    return String.join(SEPARATOR, keywords);
  }

  /** The keywords of a stored pattern, as typed. */
  public static List<String> split(String pattern) {
    List<String> out = new ArrayList<>();
    if (pattern != null) {
      for (String raw : pattern.split(Pattern.quote(SEPARATOR))) {
        if (!raw.isBlank()) {
          out.add(raw.trim());
        }
      }
    }
    return out;
  }

  /** The most specific matching keyword, unless an exclusion matches or nothing does. */
  public Optional<Hit> match(List<String> tokens) {
    if (tokens == null || tokens.isEmpty() || include.isEmpty()) {
      return Optional.empty();
    }
    for (Term t : exclude) {
      if (t.specificity(tokens) >= 0) {
        return Optional.empty();
      }
    }
    Hit best = null;
    for (Term t : include) {
      int s = t.specificity(tokens);
      if (s >= 0 && (best == null || s > best.specificity())) {
        best = new Hit(t.text(), s);
      }
    }
    return Optional.ofNullable(best);
  }

  /**
   * Why a keyword cannot be used, or empty when it can.
   *
   * @param keyword one keyword, already trimmed and upper-cased
   */
  public static Optional<String> problem(String keyword) {
    if (keyword == null || keyword.isBlank()) {
      return Optional.of("must not be blank");
    }
    if (keyword.contains(SEPARATOR)) {
      return Optional.of("may not contain |");
    }
    if (!KEYWORD_CHARS.matcher(keyword).matches()) {
      return Optional.of("may contain only letters, digits, spaces and & . ' - / *, with - or = at the start");
    }
    Optional<Term> t = term(keyword);
    if (t.isEmpty()) {
      return Optional.of("has no letters or digits to match");
    }
    Term term = t.get();
    if (term.exact() && term.stems().contains(Boolean.TRUE)) {
      return Optional.of("cannot be both exact (=) and a stem (*)");
    }
    for (int i = 0; i < term.words().size(); i++) {
      int len = term.words().get(i).length();
      if (term.stems().get(i) && len < MIN_STEM) {
        return Optional.of("a stem (*) needs at least " + MIN_STEM + " letters before the *");
      }
      if (term.words().size() == 1 && !term.exact() && len < MIN_WORD) {
        return Optional.of("must be at least " + MIN_WORD + " characters");
      }
    }
    return Optional.empty();
  }

  /** Problems with a whole keyword list: at least one keyword that is not an exclusion. */
  public static Optional<String> listProblem(List<String> keywords) {
    boolean positive =
        keywords.stream().anyMatch(k -> k != null && !k.isBlank() && !k.trim().startsWith("-"));
    return positive ? Optional.empty() : Optional.of("add at least one name that is not an exclusion (-)");
  }

  private static Optional<Term> term(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String text = raw.trim().toUpperCase(Locale.ROOT);
    if (text.isEmpty()) {
      return Optional.empty();
    }
    boolean exclude = text.startsWith("-");
    boolean exact = text.startsWith("=");
    String body = exclude || exact ? text.substring(1) : text;
    List<String> words = new ArrayList<>();
    List<Boolean> stems = new ArrayList<>();
    for (String piece : body.trim().split("\\s+")) {
      boolean stem = piece.endsWith("*");
      List<String> w = MerchantText.words(piece.replace("*", ""));
      for (int i = 0; i < w.size(); i++) {
        words.add(w.get(i));
        stems.add(stem && i == w.size() - 1);
      }
    }
    if (words.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Term(text, exclude, exact, List.copyOf(words), List.copyOf(stems)));
  }
}
