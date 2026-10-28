package com.moneyreserve.service.ingest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The portable-pattern rules an SMS parser template must meet (the V1.3.0 "PATTERNS" rules, made
 * executable).
 *
 * <p>A template runs on the server (Java) and on every handset (ICU on Android, JavaScriptCore /
 * ECMAScript 2018 on iOS). A pattern that only one engine understands parses on one platform and
 * silently not on the other, so the admin refuses it before it can be published:
 *
 * <ul>
 *   <li>anchored with {@code ^};
 *   <li>named groups only ({@code (?<name>...)}, name {@code [a-zA-Z][a-zA-Z0-9]*}); every other
 *       group is non-capturing {@code (?:...)} or a lookahead {@code (?=...)} / {@code (?!...)};
 *   <li>no inline flags, lookbehind, atomic groups, possessive quantifiers, back-references,
 *       {@code \A \Z \z \G}, Unicode properties or POSIX classes;
 *   <li>compiles in {@code java.util.regex}; at most 4,000 characters.
 * </ul>
 */
public final class TemplatePatternValidator {

  public static final int MAX_LENGTH = 4_000;

  private static final Pattern GROUP_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]*");
  private static final Pattern NAMED_GROUP = Pattern.compile("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>");

  private TemplatePatternValidator() {}

  /** Every rule the pattern breaks, in plain words; empty when it is valid and portable. */
  public static List<String> problems(String pattern) {
    List<String> out = new ArrayList<>();
    if (pattern == null || pattern.isBlank()) {
      out.add("The pattern is empty.");
      return out;
    }
    if (pattern.length() > MAX_LENGTH) {
      out.add("The pattern is longer than " + MAX_LENGTH + " characters.");
    }
    if (!pattern.startsWith("^")) {
      out.add("The pattern must start with ^ so it only matches from the start of the message.");
    }
    scan(pattern, out);
    try {
      Pattern.compile(pattern);
    } catch (PatternSyntaxException ex) {
      out.add("The pattern does not compile: " + ex.getDescription() + " near position " + ex.getIndex() + ".");
    }
    return out;
  }

  /** The compiled pattern, or {@code null} when it does not compile. */
  public static Pattern compileQuietly(String pattern) {
    try {
      return pattern == null ? null : Pattern.compile(pattern);
    } catch (PatternSyntaxException ex) {
      return null;
    }
  }

  /** Names of the capture groups, in order of appearance. */
  public static Set<String> groupNames(String pattern) {
    Set<String> out = new LinkedHashSet<>();
    if (pattern == null) {
      return out;
    }
    Matcher m = NAMED_GROUP.matcher(pattern);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  private static void scan(String p, List<String> out) {
    boolean inClass = false;
    for (int i = 0; i < p.length(); i++) {
      char c = p.charAt(i);
      if (c == '\\') {
        if (i + 1 < p.length()) {
          char n = p.charAt(i + 1);
          if (n >= '1' && n <= '9') {
            add(out, "Back-references (\\" + n + ") are not portable.");
          } else if (n == 'k') {
            add(out, "Named back-references (\\k<...>) are not portable.");
          } else if ("AZzG".indexOf(n) >= 0) {
            add(out, "\\" + n + " is not supported on iOS; use ^ or $.");
          } else if (n == 'p' || n == 'P') {
            add(out, "Unicode property classes (\\p{...}) are not portable.");
          }
        }
        i++;
        continue;
      }
      if (inClass) {
        if (c == '[' && i + 1 < p.length() && p.charAt(i + 1) == ':') {
          add(out, "POSIX classes such as [:alpha:] are not portable.");
        }
        if (c == ']') {
          inClass = false;
        }
        continue;
      }
      if (c == '[') {
        inClass = true;
        // a ']' straight after '[' or '[^' is a literal
        if (i + 1 < p.length() && p.charAt(i + 1) == '^') {
          i++;
        }
        if (i + 1 < p.length() && p.charAt(i + 1) == ']') {
          i++;
        }
        continue;
      }
      if (c == '(') {
        if (i + 1 >= p.length() || p.charAt(i + 1) != '?') {
          add(out, "Capture groups must be named, like (?<amount>...); use (?:...) otherwise.");
          continue;
        }
        char k = i + 2 < p.length() ? p.charAt(i + 2) : ' ';
        if (k == ':' || k == '=' || k == '!') {
          continue;
        }
        if (k == '<') {
          char k2 = i + 3 < p.length() ? p.charAt(i + 3) : ' ';
          if (k2 == '=' || k2 == '!') {
            add(out, "Lookbehind ((?<=...) or (?<!...)) is not portable.");
            continue;
          }
          int end = p.indexOf('>', i + 3);
          String name = end < 0 ? "" : p.substring(i + 3, end);
          if (!GROUP_NAME.matcher(name).matches()) {
            add(out, "Group name '" + name + "' must be letters and digits, starting with a letter.");
          }
          continue;
        }
        if (k == '>') {
          add(out, "Atomic groups (?>...) are not portable.");
        } else {
          add(out, "Inline flags and other (?...) constructs are not portable.");
        }
        continue;
      }
      if ((c == '*' || c == '+' || c == '?' || c == '}')
          && i + 1 < p.length()
          && p.charAt(i + 1) == '+'
          && !(c == '?' && i > 0 && p.charAt(i - 1) == '(')) {
        add(out, "Possessive quantifiers (*+, ++, ?+) are not portable.");
      }
    }
  }

  private static void add(List<String> out, String problem) {
    if (!out.contains(problem)) {
      out.add(problem);
    }
  }
}
