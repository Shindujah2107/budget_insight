package com.moipay.budgetinsights.service.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SMS format skeleton: what a handset may send about a bank message no template could read.
 *
 * <p><b>A skeleton is the shape of a message, never its content.</b> The phone replaces every
 * value with a typed placeholder and every word it does not find in the shared vocabulary with
 * {@code W}, and sends a list of tokens. Each token is one of:
 *
 * <ul>
 *   <li>a vocabulary word id with its case: {@code 4c} is word 4 written "Payment", {@code 4u}
 *       "PAYMENT", {@code 4l} "payment";
 *   <li>{@code W}: one or more words that are not in the vocabulary (names, merchants, places);
 *   <li>a value placeholder with its <em>format</em>, written only in format letters:
 *       {@code AMT:c2} (grouped with commas, 2 decimals), {@code ACCT:NNNNXXXXNNNN} (a masked
 *       account, N for a digit), {@code DATE:dd/MM/yy}, {@code TIME:hh:mm a}, {@code N:6} (any
 *       other number, by length);
 *   <li>one punctuation character from a fixed set, or {@code NL} for a line break.
 * </ul>
 *
 * <p>A leading {@code ~} means "no space before this token". Case, punctuation, spacing and value
 * formats are kept because a parser template is matched against the exact text of the SMS: a
 * template drafted from a shape that had lost them would never read a real message.
 *
 * <p><b>Why this cannot carry personal data.</b> No token can hold a digit except a vocabulary id
 * and a length; a word can only be named by an id the server issued; a format is spelt in a
 * handful of letters ({@code d M m y H h s a N X}) that cannot form a name. The grammar is the
 * control, and {@link #read} enforces it on every token. On top of that the rendered text is
 * re-checked for digits and passed through {@link OtpGuard}.
 *
 * <p>Pure: no Spring, no clock, no repository, so the tests drive it directly — the same reason
 * {@link OtpGuard} is written this way.
 */
public final class SkeletonGrammar {

  /** Fewer tokens than this cannot describe a transaction. */
  public static final int MIN_TOKENS = 4;

  /** A bank SMS is at most a few hundred characters; this is generous for one. */
  public static final int MAX_TOKENS = 120;

  /** Vocabulary words needed before a shape says anything an admin could use. */
  public static final int MIN_WORDS = 3;

  /** Longest "other number" a skeleton may describe. */
  public static final int MAX_NUMBER_LENGTH = 20;

  /** The stored key and the display text each fit a varchar(3000) column. */
  public static final int MAX_STORED_LENGTH = 3000;

  public enum Kind {
    WORD,
    W,
    AMT,
    ACCT,
    DATE,
    TIME,
    N,
    PUNCT,
    NL
  }

  /**
   * Why a skeleton was refused.
   *
   * <p>Only two answers, deliberately. {@code INVALID} covers everything from a malformed token to
   * an OTP word, and the caller is never told which: a handset whose filter let something through
   * is the component under suspicion, and telling it what tripped is telling it how to avoid it.
   */
  public enum Problem {
    /** Not a well-formed skeleton, an unknown word id, or text that fails the OTP or digit check. */
    INVALID,
    /** Well formed, but too little in it to become a template (no amount, mostly unknown words). */
    NOT_USEFUL
  }

  /** One parsed token. {@code wordId}/{@code wordCase} for WORD, {@code shape} for placeholders. */
  public record Token(boolean glued, Kind kind, int wordId, char wordCase, String shape) {}

  /**
   * A skeleton that passed.
   *
   * @param key the canonical tokens joined by {@link #KEY_SEPARATOR}; with the sender it identifies
   *     a format
   * @param display what an admin sees, e.g. {@code Dear Customer, bill Payment made from {W} …}
   * @param tokens the parsed tokens
   */
  public record Skeleton(String key, String display, List<Token> tokens) {}

  /** Either a skeleton or the reason there is none. */
  public record Result(Skeleton skeleton, Problem problem) {
    public boolean ok() {
      return skeleton != null;
    }

    static Result fail(Problem problem) {
      return new Result(null, problem);
    }
  }

  // ---------------------------------------------------------------------------------
  // The grammar
  // ---------------------------------------------------------------------------------

  /**
   * Joins tokens into the stored key. Not a space: date and time shapes contain spaces. A bar is
   * outside the grammar, so it can never be part of a token.
   */
  public static final String KEY_SEPARATOR = "|";

  private static final Pattern TOKEN =
      Pattern.compile(
          "^(~?)(?:"
              + "(?<id>[0-9]{1,5})(?<case>[ulc])"
              + "|(?<w>W)"
              + "|AMT:(?<amt>[cp][0-2])"
              + "|ACCT:(?<acct>[NX*#]{2,24})"
              + "|DATE:(?<date>[dMmy/.\\-, ]{2,24})"
              + "|TIME:(?<time>[Hhmsa: ]{2,12})"
              + "|N:(?<n>[1-9][0-9]?)"
              + "|(?<nl>NL)"
              + "|(?<p>[,.:;\\-/()'*#@&+=!?%\"])"
              + ")$");

  /** Day, month, year in any order, with the separators banks use. */
  private static final Pattern DATE_SHAPE =
      Pattern.compile(
          "^(?:dd|d|MMMM|MMM|MM|Mmmm|Mmm|M|yyyy|yy)(?:[/.\\-, ]{0,2}(?:dd|d|MMMM|MMM|MM|Mmmm|Mmm|M|yyyy|yy)){1,2}$");

  private static final Pattern DATE_PART = Pattern.compile("dd|d|MMMM|MMM|MM|Mmmm|Mmm|M|yyyy|yy|[/.\\-, ]");

  private static final Pattern TIME_SHAPE = Pattern.compile("^(?:HH|H|hh|h):mm(?::ss)?(?: ?a)?$");

  private static final Pattern TIME_PART = Pattern.compile("HH|H|hh|h|mm|ss|a|[: ]");

  private SkeletonGrammar() {}

  /**
   * Parses and checks a skeleton.
   *
   * @param raw the tokens as the phone sent them
   * @param vocabulary the words the phone may use: id → upper-case word
   */
  public static Result read(List<String> raw, Map<Integer, String> vocabulary) {
    if (raw == null || raw.size() < MIN_TOKENS || raw.size() > MAX_TOKENS) {
      return Result.fail(Problem.INVALID);
    }
    List<Token> tokens = new ArrayList<>(raw.size());
    for (String r : raw) {
      Token t = parse(r);
      if (t == null) {
        return Result.fail(Problem.INVALID);
      }
      if (t.kind() == Kind.WORD && !vocabulary.containsKey(t.wordId())) {
        return Result.fail(Problem.INVALID);
      }
      // The phone joins neighbouring unknown words into one W. Two in a row would split one
      // format into many candidates, and is not what a conforming phone sends.
      if (t.kind() == Kind.W && !tokens.isEmpty() && tokens.get(tokens.size() - 1).kind() == Kind.W) {
        return Result.fail(Problem.INVALID);
      }
      tokens.add(t);
    }

    String display = display(tokens, vocabulary);
    String key = key(tokens);
    if (display.length() > MAX_STORED_LENGTH || key.length() > MAX_STORED_LENGTH) {
      return Result.fail(Problem.INVALID);
    }
    // The grammar cannot express a digit or an unknown word, so neither check below should ever
    // fire. They are here for the day the grammar is widened carelessly.
    if (display.chars().anyMatch(Character::isDigit) || OtpGuard.trips(display)) {
      return Result.fail(Problem.INVALID);
    }

    int words = 0;
    int unknown = 0;
    int content = 0;
    boolean amount = false;
    for (Token t : tokens) {
      switch (t.kind()) {
        case WORD -> {
          words++;
          content++;
        }
        case W -> {
          unknown++;
          content++;
        }
        case AMT -> {
          amount = true;
          content++;
        }
        case PUNCT, NL -> {}
        default -> content++;
      }
    }
    if (!amount || words < MIN_WORDS || unknown * 2 > content) {
      return Result.fail(Problem.NOT_USEFUL);
    }
    return new Result(new Skeleton(key, display, List.copyOf(tokens)), null);
  }

  /**
   * The canonical spelling of the tokens, joined by {@link #KEY_SEPARATOR}.
   *
   * <p>Rebuilt from the parsed tokens rather than copied from the request, so spellings that mean
   * the same thing — {@code 04c} and {@code 4c}, a {@code ~} on the first token or after a line
   * break — are one format, not several each needing five customers of their own, and so the
   * stored key cannot carry anything the parse did not keep.
   */
  static String key(List<Token> tokens) {
    StringBuilder out = new StringBuilder();
    boolean lineStart = true;
    for (Token t : tokens) {
      if (out.length() > 0) {
        out.append(KEY_SEPARATOR);
      }
      if (t.kind() != Kind.NL && t.glued() && !lineStart) {
        out.append('~');
      }
      out.append(
          switch (t.kind()) {
            case WORD -> t.wordId() + String.valueOf(t.wordCase());
            case W -> "W";
            case AMT -> "AMT:" + t.shape();
            case ACCT -> "ACCT:" + t.shape();
            case DATE -> "DATE:" + t.shape();
            case TIME -> "TIME:" + t.shape();
            case N -> "N:" + Integer.parseInt(t.shape());
            case NL -> "NL";
            case PUNCT -> t.shape();
          });
      lineStart = t.kind() == Kind.NL;
    }
    return out.toString();
  }

  /** One token, or null if it is not in the grammar. */
  static Token parse(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > 30) {
      return null;
    }
    Matcher m = TOKEN.matcher(raw);
    if (!m.matches()) {
      return null;
    }
    boolean glued = !m.group(1).isEmpty();
    if (m.group("id") != null) {
      int id = Integer.parseInt(m.group("id"));
      return id < 1 ? null : new Token(glued, Kind.WORD, id, m.group("case").charAt(0), null);
    }
    if (m.group("w") != null) {
      return new Token(glued, Kind.W, 0, ' ', null);
    }
    if (m.group("amt") != null) {
      return new Token(glued, Kind.AMT, 0, ' ', m.group("amt"));
    }
    if (m.group("acct") != null) {
      return new Token(glued, Kind.ACCT, 0, ' ', m.group("acct"));
    }
    if (m.group("date") != null) {
      String shape = m.group("date");
      return DATE_SHAPE.matcher(shape).matches() ? new Token(glued, Kind.DATE, 0, ' ', shape) : null;
    }
    if (m.group("time") != null) {
      String shape = m.group("time");
      return TIME_SHAPE.matcher(shape).matches() ? new Token(glued, Kind.TIME, 0, ' ', shape) : null;
    }
    if (m.group("n") != null) {
      int length = Integer.parseInt(m.group("n"));
      return length > MAX_NUMBER_LENGTH ? null : new Token(glued, Kind.N, 0, ' ', m.group("n"));
    }
    if (m.group("nl") != null) {
      return new Token(false, Kind.NL, 0, ' ', null);
    }
    return new Token(glued, Kind.PUNCT, 0, ' ', m.group("p"));
  }

  // ---------------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------------

  /** What an admin reads: words in their case, placeholders in braces, formats where they matter. */
  public static String display(List<Token> tokens, Map<Integer, String> vocabulary) {
    return render(tokens, vocabulary, false);
  }

  /**
   * The skeleton filled with obviously fake values, so the existing template wizard can analyse it
   * like a pasted sample: {@code 1,234.56} for an amount, {@code 05/03/26} for a date in
   * {@code dd/MM/yy}, {@code 1234XXXX5678} for {@code NNNNXXXXNNNN}, "Sample Text" for unknown
   * words. Every value is invented here; none came from a phone.
   */
  public static String synthetic(List<Token> tokens, Map<Integer, String> vocabulary) {
    return render(tokens, vocabulary, true);
  }

  private static String render(List<Token> tokens, Map<Integer, String> vocabulary, boolean fake) {
    StringBuilder out = new StringBuilder();
    boolean lineStart = true;
    for (Token t : tokens) {
      if (t.kind() == Kind.NL) {
        out.append('\n');
        lineStart = true;
        continue;
      }
      if (!lineStart && !t.glued()) {
        out.append(' ');
      }
      lineStart = false;
      switch (t.kind()) {
        case WORD -> out.append(cased(vocabulary.getOrDefault(t.wordId(), "?"), t.wordCase()));
        case W -> out.append(fake ? "Sample Text" : "{W}");
        case AMT -> out.append(fake ? amount(t.shape()) : "{AMT}");
        case ACCT -> out.append(fake ? account(t.shape()) : "{ACCT:" + t.shape() + "}");
        case DATE -> out.append(fake ? date(t.shape()) : "{DATE:" + t.shape() + "}");
        case TIME -> out.append(fake ? time(t.shape()) : "{TIME:" + t.shape() + "}");
        case N -> out.append(fake ? digits(Integer.parseInt(t.shape())) : "{N}");
        case PUNCT -> out.append(t.shape());
        default -> {}
      }
    }
    return out.toString();
  }

  static String cased(String word, char wordCase) {
    String upper = word.toUpperCase(Locale.ROOT);
    return switch (wordCase) {
      case 'l' -> upper.toLowerCase(Locale.ROOT);
      case 'c' -> upper.charAt(0) + upper.substring(1).toLowerCase(Locale.ROOT);
      default -> upper;
    };
  }

  static String amount(String shape) {
    boolean grouped = shape.charAt(0) == 'c';
    int decimals = shape.charAt(1) - '0';
    String whole = grouped ? "1,234" : "1234";
    return switch (decimals) {
      case 1 -> whole + ".5";
      case 2 -> whole + ".56";
      default -> whole;
    };
  }

  static String account(String shape) {
    StringBuilder out = new StringBuilder();
    int digit = 1;
    for (char c : shape.toCharArray()) {
      if (c == 'N') {
        out.append(digit % 10);
        digit++;
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  static String digits(int length) {
    StringBuilder out = new StringBuilder();
    for (int i = 1; i <= length; i++) {
      out.append(i % 10);
    }
    return out.toString();
  }

  /** 5 March 2026, in the given shape. Day 5 and month 3 keep d/dd and M/MM distinguishable. */
  static String date(String shape) {
    StringBuilder out = new StringBuilder();
    Matcher m = DATE_PART.matcher(shape);
    while (m.find()) {
      out.append(
          switch (m.group()) {
            case "d" -> "5";
            case "dd" -> "05";
            case "M" -> "3";
            case "MM" -> "03";
            case "MMM" -> "MAR";
            case "Mmm" -> "Mar";
            case "MMMM" -> "MARCH";
            case "Mmmm" -> "March";
            case "yy" -> "26";
            case "yyyy" -> "2026";
            default -> m.group();
          });
    }
    return out.toString();
  }

  /** 2:35:20 PM, in the given shape. */
  static String time(String shape) {
    StringBuilder out = new StringBuilder();
    Matcher m = TIME_PART.matcher(shape);
    while (m.find()) {
      out.append(
          switch (m.group()) {
            case "HH", "H" -> "14";
            case "hh" -> "02";
            case "h" -> "2";
            case "mm" -> "35";
            case "ss" -> "20";
            case "a" -> "PM";
            default -> m.group();
          });
    }
    return out.toString();
  }

  // ---------------------------------------------------------------------------------
  // Identity
  // ---------------------------------------------------------------------------------

  /** Sender plus canonical tokens: two banks with the same wording are two formats. */
  public static String formatHash(String senderId, String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (senderId.toUpperCase(Locale.ROOT) + "|" + key).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  /** Re-parses stored canonical tokens (they passed {@link #read} when they were stored). */
  public static List<Token> tokensOf(String key) {
    List<Token> out = new ArrayList<>();
    for (String raw : key.split(Pattern.quote(KEY_SEPARATOR))) {
      Token t = parse(raw);
      if (t != null) {
        out.add(t);
      }
    }
    return out;
  }
}
