package com.moipay.budgetinsights.service.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The server-side OTP and PIN re-check (BI-09).
 *
 * <p>This is the second of three controls, and the only one MoiPay operates. The device filter
 * discards an OTP before its parser ever sees the message; the ingest contract has no free-text
 * field, so there is no field designed to carry message content; and this guard re-reads every
 * field that does arrive, on the assumption that both of the others have failed. Compliance
 * required the re-check explicitly, on 6 August 2026, as defence in depth.
 *
 * <p><b>Both kinds of mistake are expensive, and they pull in opposite directions.</b> A miss puts
 * a one-time code into {@code bi_transaction.merchant}, where the breakdown endpoint will serve it
 * back. A false positive is worse than it looks: the customer's spend silently disappears from
 * their total, the response says only "could not be processed" by design, and a permanent
 * {@code OTP_DETECTED} row is written to the one table a regulator reads — for a grocery run. The
 * rules below are shaped by that second failure at least as much as the first.
 *
 * <p><b>Matching is per word, not per substring.</b> Three-letter tokens are the reason. "SPOT
 * PIZZA" and "HOT POT" both contain the letters o-t-p once their separators are removed, and a
 * merchant with a branch name and a postal code — "CARGILLS FOOD CITY THALAWATHUGODA 10116" — is a
 * long string with a digit run in it. Neither may be refused.
 *
 * <p><b>Pure and static by design.</b> No clock, no repository, no Spring. A compliance control
 * that can be exercised by a test with no context around it is a control that actually gets
 * exercised, and the release-gate test (BI-32) drives this class directly.
 *
 * <p><b>What this class must never do is echo what it found.</b> It answers a boolean. The matched
 * token is not returned, not logged and not put in the response — telling a caller which rule it
 * tripped is telling an attacker how to dress a payload so it does not.
 */
public final class OtpGuard {

  private OtpGuard() {}

  /**
   * Words that mean a code on their own, matched against a whole word.
   *
   * <p>Whole-word and not substring: "pin" lives inside "shopping", "otp" inside "spot pizza",
   * "cvc" inside plenty of product codes. Stripping the separators from the word first is what
   * makes "O.T.P." and "m-PIN" the same word as "otp" and "mpin".
   */
  private static final Set<String> CODE_WORDS =
      Set.of(
          "otp", "otps",
          "pin", "pins", "mpin", "ipin", "epin",
          "passcode", "password", "passwd", "pwd",
          "cvv", "cvv2", "cvc", "cvc2");

  /**
   * Phrases that survive having every separator removed.
   *
   * <p>These are matched against the whole value rather than a single word, because the phrase is
   * spread over several. Every entry is long and specific enough that a substring match cannot
   * plausibly land inside a merchant name — which is precisely the property the three-letter tokens
   * above do not have.
   */
  private static final List<String> CODE_PHRASES =
      List.of(
          "onetimepassword",
          "onetimepasscode",
          "onetimepin",
          "onetimecode",
          "verificationcode",
          "verificationpin",
          "securitycode",
          "securitypin",
          "authenticationcode",
          "authcode",
          "accesscode",
          "logincode");

  /**
   * Words that mean a code only when a code is actually present.
   *
   * <p>"Code" alone is not evidence — but "Your code: 839201" is, and that three-word fragment is
   * exactly what a mis-firing capture group puts in the merchant field. Requiring a digit run
   * alongside is what keeps these usable without rejecting a shop called something innocuous.
   */
  private static final Set<String> CODE_CONTEXT_WORDS =
      Set.of(
          "code", "codes", "secret", "verify", "verification", "verifying",
          "authorise", "authorize", "authenticate", "login", "logon", "expires", "expiry");

  /** A standalone run of four to eight digits — the shape of a code. */
  private static final Pattern CODE_RUN = Pattern.compile("(?<![0-9])[0-9]{4,8}(?![0-9])");

  /** Where the last such run sits, so a trailing store or postal number can be told apart. */
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /**
   * How many words make a value read as a sentence rather than a name.
   *
   * <p>Only used by the last rule, and only together with the position of the digit run.
   */
  private static final int SENTENCE_WORDS = 4;

  /** True when any field trips the guard. Null and blank fields are ignored, not tripped. */
  public static boolean trips(String... fields) {
    if (fields == null) {
      return false;
    }
    for (String field : fields) {
      if (trips(field)) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when this single value looks like it carries an OTP, a PIN, or the message that contained
   * one.
   *
   * <p>Four rules, cheapest and most certain first:
   *
   * <ol>
   *   <li>A whole word is a code word — "OTP", "O.T.P.", "PIN", "mPIN", "CVV".
   *   <li>A code phrase survives separator removal — "one-time password", "verification code".
   *   <li>A context word appears <em>and</em> the value carries a four-to-eight digit run — "Your
   *       code: 839201", which rule 1 misses because "code" alone means nothing.
   *   <li>The value reads like a sentence and carries a digit run that is <b>not</b> its last word.
   *       This is the backstop for a leak with no keyword at all — "Use 839201 to log in" — and the
   *       position test is what keeps "CARGILLS FOOD CITY THALAWATHUGODA 10116" out of it: a
   *       merchant's branch or postal number is the end of the string, an embedded code is not.
   * </ol>
   */
  public static boolean trips(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }

    List<String> words = words(value);

    for (String word : words) {
      if (CODE_WORDS.contains(word)) {
        return true;
      }
    }

    String joined = String.join("", words);
    for (String phrase : CODE_PHRASES) {
      if (joined.contains(phrase)) {
        return true;
      }
    }

    boolean hasCodeRun = CODE_RUN.matcher(value).find();
    if (hasCodeRun) {
      for (String word : words) {
        if (CODE_CONTEXT_WORDS.contains(word)) {
          return true;
        }
      }
    }

    return hasCodeRun && words.size() >= SENTENCE_WORDS && !codeRunIsTrailing(value);
  }

  /**
   * The value split into words, each lower-cased and stripped of everything that is not a letter or
   * a digit.
   *
   * <p>Runs of single-character words are joined back together first, so "o t p" is one word. That
   * is the one evasion the per-word rule would otherwise open, and closing it costs nothing:
   * genuine merchant names do not consist of single letters in a row.
   *
   * <p>{@link Locale#ROOT} rather than the default locale: in a Turkish locale the I in "PIN"
   * lower-cases to a dotless ı and the match silently stops working. A compliance control that
   * depends on the container's locale is not a control.
   */
  private static List<String> words(String value) {
    List<String> stripped = new ArrayList<>();
    for (String raw : WHITESPACE.split(value.trim())) {
      String word = stripToAlphanumeric(raw);
      if (!word.isEmpty()) {
        stripped.add(word);
      }
    }

    List<String> merged = new ArrayList<>(stripped.size());
    StringBuilder run = new StringBuilder();
    for (String word : stripped) {
      if (word.length() == 1) {
        run.append(word);
        continue;
      }
      if (run.length() > 0) {
        merged.add(run.toString());
        run.setLength(0);
      }
      merged.add(word);
    }
    if (run.length() > 0) {
      merged.add(run.toString());
    }
    return merged;
  }

  /**
   * True when the value's last word is the digit run.
   *
   * <p>"KEELLS SUPER KOLLUPITIYA COLOMBO 00300" ends in its postal code; "Use 839201 to log in to
   * your account" does not end in its code. That difference is the whole of rule 4's precision, and
   * it is a heuristic — which is why rule 4 also needs the sentence length, and why it is last.
   */
  private static boolean codeRunIsTrailing(String value) {
    String[] parts = WHITESPACE.split(value.trim());
    if (parts.length == 0) {
      return false;
    }
    String last = stripToAlphanumeric(parts[parts.length - 1]);
    return !last.isEmpty() && CODE_RUN.matcher(last).matches();
  }

  private static String stripToAlphanumeric(String value) {
    StringBuilder out = new StringBuilder(value.length());
    for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
      if (Character.isLetterOrDigit(c)) {
        out.append(c);
      }
    }
    return out.toString();
  }
}
