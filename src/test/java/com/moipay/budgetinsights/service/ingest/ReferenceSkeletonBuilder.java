package com.moipay.budgetinsights.service.ingest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference implementation of what a phone does: SMS text in, skeleton tokens out.
 *
 * <p><b>Test code on purpose.</b> The server never builds skeletons — it never has an SMS to build
 * one from — and a class in {@code src/main} that reads message text would be a capability the
 * service is designed not to have. This lives here so that the grammar ({@link SkeletonGrammar}) is
 * tested against realistic input, and so the mobile team has an executable specification to port:
 * {@code src/test/resources/format-discovery/skeleton-golden.tsv} is generated from it, and the
 * Android implementation must produce the same tokens for every row.
 *
 * <p>Rules, in the order they are tried at each position of the text:
 *
 * <ol>
 *   <li>time {@code 01:51 PM} → {@code TIME:hh:mm a};
 *   <li>date {@code 05 October 2026} → {@code DATE:dd Mmmm yyyy};
 *   <li>masked account {@code 2003XXXXX382} → {@code ACCT:NNNNXXXXXNNN};
 *   <li>amount: a number shaped like money ({@code 1,000}, {@code 99.50}) or any number right after
 *       a currency word → {@code AMT:c2} (c = comma grouped, p = plain; 0–2 decimals);
 *   <li>any other digits → {@code N:length};
 *   <li>a word in the vocabulary → its id and case; any other word → {@code W};
 *   <li>a line break → {@code NL}; punctuation from the fixed set → itself; anything else is a gap.
 * </ol>
 *
 * <p>A token written with no whitespace before it gets a {@code ~} prefix. Neighbouring unknown
 * words, and the joiners inside a name ({@code . ' & - /} with no spaces around them), collapse into
 * one {@code W}.
 */
final class ReferenceSkeletonBuilder {

  private static final Set<String> CURRENCIES = Set.of("LKR", "RS", "USD", "EUR", "GBP");
  private static final String MONTH =
      "(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Za-z]{0,6}";
  private static final String PUNCTUATION = ",.:;-/()'*#@&+=!?%\"";
  private static final Set<String> NAME_JOINERS = Set.of(".", "'", "&", "-", "/");

  private static final Pattern SCAN =
      Pattern.compile(
          "(?<time>(?<![0-9])[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?: ?[AaPp][Mm])?(?![0-9A-Za-z]))"
              + "|(?<date>(?<![0-9A-Za-z])(?:"
              + "[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}"
              + "|[0-9]{1,2}[/.\\-][0-9]{1,2}[/.\\-](?:[0-9]{4}|[0-9]{2})"
              + "|[0-9]{1,2}[ \\-]?(?i:" + MONTH + ")[ ,\\-]*(?:[0-9]{4}|[0-9]{2})"
              + "|(?i:" + MONTH + ") [0-9]{1,2},? [0-9]{4}"
              + ")(?![0-9A-Za-z]))"
              + "|(?<acct>(?<![0-9A-Za-z])(?=[0-9Xx*#]*[0-9*#])[0-9Xx*#]*[Xx*#]{2,}[0-9Xx*#]*(?![0-9A-Za-z]))"
              + "|(?<money>(?<![0-9])(?:[0-9]{1,3}(?:,[0-9]{3})+(?:\\.[0-9]{1,2})?|[0-9]+\\.[0-9]{1,2})(?![0-9]))"
              + "|(?<num>[0-9]+)"
              + "|(?<word>[A-Za-z]+)"
              + "|(?<nl>\\R)"
              + "|(?<space>[ \\t\\u00A0]+)"
              + "|(?<other>.)",
          Pattern.DOTALL);

  private final Map<String, Integer> vocabulary;

  /** The token text of each currency word in any case, e.g. "9u", "9c", "9l" for LKR. */
  private final Set<String> currencyTokens = new java.util.HashSet<>();

  /** @param vocabulary upper-case word → id */
  ReferenceSkeletonBuilder(Map<String, Integer> vocabulary) {
    this.vocabulary = vocabulary;
    for (String c : CURRENCIES) {
      Integer id = vocabulary.get(c);
      if (id != null) {
        currencyTokens.add(id + "u");
        currencyTokens.add(id + "c");
        currencyTokens.add(id + "l");
      }
    }
  }

  List<String> build(String sms) {
    String text = Normalizer.normalize(sms, Normalizer.Form.NFKC);
    List<String> out = new ArrayList<>();
    boolean spaceBefore = true; // the first token never carries ~
    Matcher m = SCAN.matcher(text);
    while (m.find()) {
      if (m.group("space") != null) {
        spaceBefore = true;
        continue;
      }
      if (m.group("nl") != null) {
        out.add("NL");
        spaceBefore = true;
        continue;
      }
      String token;
      if (m.group("time") != null) {
        token = "TIME:" + timeShape(m.group("time"));
      } else if (m.group("date") != null) {
        token = "DATE:" + dateShape(m.group("date"));
      } else if (m.group("acct") != null) {
        token = "ACCT:" + m.group("acct").replaceAll("[0-9]", "N").replace('x', 'X');
      } else if (m.group("money") != null) {
        token = "AMT:" + amountShape(m.group("money"));
      } else if (m.group("num") != null) {
        String digits = m.group("num");
        token =
            afterCurrency(out)
                ? "AMT:p0"
                : "N:" + Math.min(digits.length(), SkeletonGrammar.MAX_NUMBER_LENGTH);
      } else if (m.group("word") != null) {
        token = word(m.group("word"));
      } else {
        String c = m.group("other");
        if (PUNCTUATION.contains(c)) {
          token = c;
        } else {
          // Not in the grammar (emoji, currency symbols, braces): a gap, like a space.
          spaceBefore = true;
          continue;
        }
      }
      boolean glued = !spaceBefore && !out.isEmpty() && !"NL".equals(out.get(out.size() - 1));
      out.add(glued ? "~" + token : token);
      spaceBefore = false;
    }
    List<String> tokens = collapseUnknown(out);
    // A phone sends only what the server's grammar accepts: an over-long message, or a token the
    // grammar cannot express (a 30-character masked number), means no skeleton at all — the SMS
    // simply stays queued on the phone.
    if (tokens.size() > SkeletonGrammar.MAX_TOKENS
        || tokens.stream().anyMatch(t -> SkeletonGrammar.parse(t) == null)) {
      return List.of();
    }
    return tokens;
  }

  private String word(String w) {
    Integer id = vocabulary.get(w.toUpperCase(Locale.ROOT));
    if (id == null) {
      return "W";
    }
    char wordCase;
    if (w.equals(w.toUpperCase(Locale.ROOT))) {
      wordCase = 'u';
    } else if (w.equals(w.toLowerCase(Locale.ROOT))) {
      wordCase = 'l';
    } else {
      wordCase = 'c';
    }
    return id + String.valueOf(wordCase);
  }

  /** A plain number straight after LKR / Rs / Rs. is an amount, whatever its shape. */
  private boolean afterCurrency(List<String> out) {
    int i = out.size() - 1;
    if (i >= 0 && strip(out.get(i)).equals(".")) {
      i--;
    }
    return i >= 0 && currencyTokens.contains(strip(out.get(i)));
  }

  private static String strip(String token) {
    return token.startsWith("~") ? token.substring(1) : token;
  }

  /** W W → W; W~.~W, W~'~W, W~-~W, W~/~W → W; and W & W (spaced or not) → W. */
  private static List<String> collapseUnknown(List<String> in) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < in.size(); i++) {
      String t = in.get(i);
      String bare = strip(t);
      boolean previousUnknown = !out.isEmpty() && strip(out.get(out.size() - 1)).equals("W");
      if (bare.equals("W") && previousUnknown) {
        continue;
      }
      boolean joiner =
          (t.startsWith("~") && NAME_JOINERS.contains(bare)) || bare.equals("&");
      if (previousUnknown && joiner && i + 1 < in.size() && strip(in.get(i + 1)).equals("W")) {
        i++; // skip the joiner and the glued unknown word after it
        continue;
      }
      out.add(t);
    }
    return out;
  }

  static String amountShape(String money) {
    int dot = money.indexOf('.');
    int decimals = dot < 0 ? 0 : money.length() - dot - 1;
    return (money.contains(",") ? "c" : "p") + decimals;
  }

  static String timeShape(String t) {
    boolean twelve = t.toUpperCase(Locale.ROOT).endsWith("M");
    String clock = twelve ? t.replaceAll("(?i) ?[AP]M$", "") : t;
    String[] parts = clock.split(":");
    String hour = (twelve ? "h" : "H").repeat(parts[0].length());
    StringBuilder shape = new StringBuilder(hour).append(":mm");
    if (parts.length == 3) {
      shape.append(":ss");
    }
    if (twelve) {
      shape.append(t.matches("(?i).* [AP]M$") ? " a" : "a");
    }
    return shape.toString();
  }

  /** Day-first unless the text starts with a four-digit year or a month name. */
  static String dateShape(String d) {
    List<String> runs = new ArrayList<>();
    Matcher r = Pattern.compile("[0-9]+|[A-Za-z]+|[^0-9A-Za-z]+").matcher(d);
    while (r.find()) {
      runs.add(r.group());
    }
    List<String> roles = new ArrayList<>();
    String first = runs.get(0);
    if (Character.isLetter(first.charAt(0))) {
      roles.addAll(List.of("M", "d", "y"));
    } else if (first.length() == 4) {
      roles.addAll(List.of("y", "M", "d"));
    } else {
      roles.addAll(List.of("d", "M", "y"));
    }
    StringBuilder shape = new StringBuilder();
    int field = 0;
    for (String run : runs) {
      char c = run.charAt(0);
      if (!Character.isLetterOrDigit(c)) {
        shape.append(run.replaceAll(" +", " "));
        continue;
      }
      String role = roles.get(Math.min(field++, 2));
      if (Character.isLetter(c)) {
        boolean upper = run.equals(run.toUpperCase(Locale.ROOT));
        shape.append(run.length() <= 3 ? (upper ? "MMM" : "Mmm") : (upper ? "MMMM" : "Mmmm"));
      } else if (role.equals("y")) {
        shape.append(run.length() == 4 ? "yyyy" : "yy");
      } else if (role.equals("M")) {
        shape.append(run.length() == 1 ? "M" : "MM");
      } else {
        shape.append(run.length() == 1 ? "d" : "dd");
      }
    }
    return shape.toString();
  }
}
