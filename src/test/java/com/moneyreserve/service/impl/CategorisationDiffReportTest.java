package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.service.CategorisationInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Before/after categorisation report for DEV data. Skipped unless {@code -Ddev.merchants=<file>}
 * points at the export from {@code scripts/dev-merchant-export.sql}:
 *
 * <pre>
 *   mvn -Dtest=CategorisationDiffReportTest -Ddev.merchants=dev-merchants.csv test
 * </pre>
 *
 * <p>Writes {@code target/categorisation-diff.md}: how many transactions keep their category, move
 * from OTHER to a category, move between categories (review these), or fall to OTHER.
 */
@DisplayName("DEV categorisation before/after report (opt-in)")
class CategorisationDiffReportTest {

  private record Row(String merchant, String messageType, String direction, String before, long txns, long customers) {}

  @Test
  void report() throws IOException {
    String file = System.getProperty("dev.merchants");
    assumeTrue(file != null && !file.isBlank(), "set -Ddev.merchants=<export file> to run");
    List<Row> rows = new ArrayList<>();
    for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
      String[] f = line.split("\\|", -1);
      if (f.length < 6 || f[0].isBlank()) {
        continue;
      }
      rows.add(new Row(f[0], f[1], f[2], f[3].isBlank() ? "OTHER" : f[3], Long.parseLong(f[4].trim()), Long.parseLong(f[5].trim())));
    }
    CategorisationServiceImpl engine = MerchantCategorisationGoldenTest.serviceWithMigratedRules();

    long total = 0, same = 0, gained = 0, moved = 0, lost = 0;
    Map<String, Long> transitions = new TreeMap<>();
    List<String> movedRows = new ArrayList<>();
    List<String> lostRows = new ArrayList<>();
    List<String> gainedRows = new ArrayList<>();
    List<String> stillOther = new ArrayList<>();
    for (Row r : rows) {
      TxnDirection d = "CREDIT".equalsIgnoreCase(r.direction()) ? TxnDirection.CREDIT : TxnDirection.DEBIT;
      String type = r.messageType() == null || r.messageType().isBlank() ? null : r.messageType();
      String after = engine.categorise(new CategorisationInput(r.merchant(), type, d)).getCode();
      total += r.txns();
      String line = String.format("| %s | %s | %s | %s → **%s** | %d |", r.merchant(), type == null ? "" : type, r.direction(), r.before(), after, r.txns());
      if (after.equals(r.before())) {
        same += r.txns();
        if ("OTHER".equals(after)) {
          stillOther.add(line);
        }
      } else {
        transitions.merge(r.before() + " → " + after, r.txns(), Long::sum);
        if ("OTHER".equals(r.before())) {
          gained += r.txns();
          gainedRows.add(line);
        } else if ("OTHER".equals(after)) {
          lost += r.txns();
          lostRows.add(line);
        } else {
          moved += r.txns();
          movedRows.add(line);
        }
      }
    }
    StringBuilder md = new StringBuilder();
    md.append("# DEV categorisation before/after (V1.12.0 rules)\n\n");
    md.append(String.format("Distinct merchant rows: %d · transactions: %d%n%n", rows.size(), total));
    md.append("| Outcome | Transactions | Share |\n|---|---|---|\n");
    for (Object[] o :
        new Object[][] {
          {"Unchanged", same}, {"OTHER → a category (gained)", gained},
          {"Category → another category (review)", moved}, {"Category → OTHER (review)", lost}
        }) {
      long n = (Long) o[1];
      md.append(String.format("| %s | %d | %.1f%% |%n", o[0], n, total == 0 ? 0 : n * 100.0 / total));
    }
    md.append("\n## Transitions\n\n| Before → after | Transactions |\n|---|---|\n");
    transitions.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
    section(md, "Moved between categories (review each)", movedRows);
    section(md, "Moved to OTHER (review each)", lostRows);
    section(md, "Newly categorised", gainedRows);
    section(md, "Still OTHER (candidates for new keywords)", stillOther);
    Path out = Path.of("target", "categorisation-diff.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
    System.out.println("Categorisation diff written to " + out.toAbsolutePath());
  }

  private static void section(StringBuilder md, String title, List<String> rows) {
    md.append("\n## ").append(title).append(" (").append(rows.size()).append(")\n\n");
    if (rows.isEmpty()) {
      md.append("None.\n");
      return;
    }
    md.append("| Merchant | Message type | Direction | Before → after | Transactions |\n|---|---|---|---|---|\n");
    rows.stream().limit(300).forEach(r -> md.append(r).append('\n'));
  }
}
