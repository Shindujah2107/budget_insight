package com.moipay.budgetinsights.service.calc;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 1 of categorisation: a bank's merchant text reduced to the words that identify the
 * merchant, so one set of keywords works for every bank's spelling of it.
 *
 * <p>Bank alerts carry the same merchant in many shapes: {@code PH *DOMINOS PIZZA SRI, LK}, {@code
 * WATER-BOARD}, {@code MCDONALD'S}, {@code HIGGSFIELD INC. +14088370029 US}. Cleaning:
 *
 * <ol>
 *   <li>Unicode compatibility form and upper case;
 *   <li>a known card-processor prefix ending in {@code *} is dropped ({@code PH *}, {@code SQ *},
 *       {@code PAYPAL *}); any other {@code NAME*} is kept, because it is usually the brand
 *       ({@code UBER *TRIP}, {@code KEELLS*SUPER});
 *   <li>{@code &} becomes {@code AND}; apostrophes are removed ({@code MCDONALD'S} → {@code
 *       MCDONALDS});
 *   <li>every other character that is not a letter or digit separates words;
 *   <li>numbers of five or more digits (phone, reference, terminal) are dropped;
 *   <li>trailing two/three-letter country codes are dropped ({@code LK}, {@code US}, {@code SG}).
 * </ol>
 *
 * <p>Pure functions, no Spring, shared by the categoriser, the admin rule test and the
 * uncategorised-merchant report so all three agree on what a merchant "is".
 */
public final class MerchantText {

  /** Country codes card networks append to the merchant location. Removed only at the end. */
  static final Set<String> COUNTRY_CODES =
      Set.of(
          "LK", "LKA", "SL", "US", "USA", "SG", "SGP", "GB", "UK", "IN", "IND", "AE", "UAE", "AU",
          "NL", "IE", "HK", "MY", "TH", "JP", "CN", "DE", "FR", "CA");

  /** Payment-processor codes card networks put before the merchant (Square, PayPal, Payhere…). */
  static final List<String> PROCESSOR_CODES =
      List.of("PH", "SQ", "SP", "TST", "PP", "PAYPAL", "IZ", "PY", "CKO", "ZTL", "SMP", "DNH");

  private static final Pattern PROCESSOR_PREFIX =
      Pattern.compile("^\\s*(?:" + String.join("|", PROCESSOR_CODES) + ")\\s*\\*\\s*(\\S.*)$");
  private static final Pattern APOSTROPHES = Pattern.compile("['\\u2018\\u2019`]");
  private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
  private static final int MIN_DROPPED_NUMBER = 5;

  private MerchantText() {}

  /** The merchant's words after cleaning; empty for null, blank or nothing-but-noise input. */
  public static List<String> tokens(String raw) {
    if (raw == null || raw.isBlank()) {
      return new ArrayList<>();
    }
    String s = upper(raw);
    Matcher prefix = PROCESSOR_PREFIX.matcher(s);
    if (prefix.matches()) {
      s = prefix.group(1);
    }
    List<String> out = new ArrayList<>();
    for (String t : split(s)) {
      if (t.length() >= MIN_DROPPED_NUMBER && t.chars().allMatch(Character::isDigit)) {
        continue;
      }
      out.add(t);
    }
    while (out.size() > 1 && COUNTRY_CODES.contains(out.get(out.size() - 1))) {
      out.remove(out.size() - 1);
    }
    return out;
  }

  /**
   * The words of a keyword an admin typed: the same case, {@code &}, apostrophe and separator rules
   * as {@link #tokens}, but nothing is dropped, so a keyword means exactly what was typed.
   */
  public static List<String> words(String raw) {
    return raw == null ? new ArrayList<>() : split(upper(raw));
  }

  private static String upper(String raw) {
    return Normalizer.normalize(raw, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
  }

  private static List<String> split(String upper) {
    String s = APOSTROPHES.matcher(upper.replace("&", " AND ")).replaceAll("");
    List<String> out = new ArrayList<>();
    for (String t : SEPARATORS.split(s)) {
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return out;
  }

  /** The cleaned merchant as one string (words joined by a space), or null when nothing is left. */
  public static String clean(String raw) {
    List<String> t = tokens(raw);
    return t.isEmpty() ? null : String.join(" ", t);
  }
}
