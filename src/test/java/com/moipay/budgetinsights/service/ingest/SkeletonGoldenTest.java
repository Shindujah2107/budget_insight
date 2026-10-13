package com.moipay.budgetinsights.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end promise of format discovery, without a phone or a database.
 *
 * <p>For every row of {@code format-discovery/skeleton-golden.tsv}: the reference builder turns the
 * SMS into exactly the expected tokens (this is the contract the Android code must also meet); the
 * server's grammar accepts them; and, where the row names a seeded template, the synthetic sample
 * the admin would start from is read by that template — as is the real SMS. That last check is the
 * point: a template drafted from a skeleton reads the messages still waiting on the phones.
 */
@DisplayName("Skeleton golden file")
class SkeletonGoldenTest {

  private static final String GOLDEN = "/format-discovery/skeleton-golden.tsv";
  private static final String TEMPLATES = "/db/migration/common/V1.3.0__bank_sms_parser_seed.sql";

  private record Row(String templateId, String sms, List<String> expected) {}

  private static List<Row> rows() {
    List<Row> out = new ArrayList<>();
    for (String line : SmsVocabularyFixture.resource(GOLDEN).split("\n")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split("\t");
      out.add(new Row(p[0], p[1].replace("\\n", "\n"), List.of(p[2].split("\\|"))));
    }
    return out;
  }

  private static String templatePattern(String id) {
    Matcher m =
        Pattern.compile(
                "'" + Pattern.quote(id) + "'::uuid, '[^']+', '[^']+',\\s*\\$re\\$(.*?)\\$re\\$",
                Pattern.DOTALL)
            .matcher(SmsVocabularyFixture.resource(TEMPLATES));
    assertTrue(m.find(), "template " + id + " not found in " + TEMPLATES);
    return m.group(1);
  }

  @Test
  @DisplayName("the golden file is not empty and covers every seeded template")
  void covered() {
    List<Row> rows = rows();
    assertTrue(rows.size() >= 11, "golden rows: " + rows.size());
    assertEquals(11, rows.stream().filter(r -> !r.templateId().equals("-")).count());
  }

  @Test
  @DisplayName("the reference builder produces exactly the golden tokens")
  void builderMatchesGolden() {
    ReferenceSkeletonBuilder builder = new ReferenceSkeletonBuilder(SmsVocabularyFixture.byWord());
    for (Row row : rows()) {
      assertEquals(String.join("|", row.expected()), String.join("|", builder.build(row.sms())), row.sms());
    }
  }

  @Test
  @DisplayName("the server grammar accepts every golden skeleton")
  void grammarAccepts() {
    for (Row row : rows()) {
      SkeletonGrammar.Result r = SkeletonGrammar.read(row.expected(), SmsVocabularyFixture.byId());
      assertTrue(r.ok(), row.sms() + " -> " + r.problem());
    }
  }

  @Test
  @DisplayName("no digit of the real SMS survives into what the server stores")
  void nothingOfTheMessageSurvives() {
    for (Row row : rows()) {
      SkeletonGrammar.Result r = SkeletonGrammar.read(row.expected(), SmsVocabularyFixture.byId());
      assertFalse(r.skeleton().display().matches("(?s).*[0-9].*"), r.skeleton().display());
      assertFalse(r.skeleton().display().contains("KEELLS"), r.skeleton().display());
      assertFalse(r.skeleton().display().contains("PERERA"), r.skeleton().display());
    }
  }

  @Test
  @DisplayName("a seeded template reads both the real SMS and the synthetic sample made from its skeleton")
  void templatesReadTheSyntheticSample() {
    for (Row row : rows()) {
      if (row.templateId().equals("-")) {
        continue;
      }
      Pattern template = Pattern.compile(templatePattern(row.templateId()));
      SkeletonGrammar.Result r = SkeletonGrammar.read(row.expected(), SmsVocabularyFixture.byId());
      String synthetic = SkeletonGrammar.synthetic(r.skeleton().tokens(), SmsVocabularyFixture.byId());
      assertTrue(template.matcher(row.sms()).find(), "template does not read the real SMS: " + row.sms());
      assertTrue(template.matcher(synthetic).find(), "template does not read the synthetic sample: " + synthetic);
    }
  }
}
