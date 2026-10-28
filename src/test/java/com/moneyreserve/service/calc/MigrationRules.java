package com.moneyreserve.service.calc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The merchant rules exactly as migration V1.12.0 inserts them, read from the migration file on
 * the classpath, so the golden regression tests always test the rules that ship.
 */
public final class MigrationRules {

  public static final String MIGRATION = "db/migration/common/V1.12.0__merchant_rules_terms.sql";

  /** One migrated rule. */
  public record Rule(String id, String categoryCode, int priority, String level, List<String> names) {
    public String pattern() {
      return MerchantTerms.join(names);
    }
  }

  private static final Pattern ROW =
      Pattern.compile(
          "\\('([0-9a-f-]{36})'::uuid,\\s*'([A-Z_]+)',\\s*(\\d+)::smallint,\\s*'([A-Z]+)',\\s*ARRAY\\[(.*?)\\]\\)",
          Pattern.DOTALL);
  private static final Pattern NAME = Pattern.compile("'((?:[^']|'')*)'");

  private MigrationRules() {}

  public static List<Rule> load() {
    try (InputStream in = MigrationRules.class.getClassLoader().getResourceAsStream(MIGRATION)) {
      if (in == null) {
        throw new IllegalStateException(MIGRATION + " is not on the classpath");
      }
      return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static List<Rule> parse(String sql) {
    List<Rule> out = new ArrayList<>();
    Matcher m = ROW.matcher(sql);
    while (m.find()) {
      List<String> names = new ArrayList<>();
      Matcher n = NAME.matcher(m.group(5));
      while (n.find()) {
        names.add(n.group(1).replace("''", "'"));
      }
      out.add(new Rule(m.group(1), m.group(2), Integer.parseInt(m.group(3)), m.group(4), List.copyOf(names)));
    }
    return out;
  }

  /** One row of a golden CSV: {@code merchant|messageType|direction|expected|note}. */
  public record Case(String merchant, String messageType, String direction, String expected, String note) {}

  public static List<Case> cases(String resource) {
    try (InputStream in = MigrationRules.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " is not on the classpath");
      }
      List<Case> out = new ArrayList<>();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] f = line.split("\\|", -1);
        out.add(new Case(f[0], blank(f[1]), blank(f[2]), f[3], f.length > 4 ? f[4] : ""));
      }
      return out;
    } catch (IOException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String blank(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
