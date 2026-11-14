package com.moneyreserve.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moneyreserve.service.ingest.RedactedSampleSet.Sample;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Template wizard: analyser + generator (architecture doc §7.7.2–7.7.5). */
@DisplayName("Template builder: analyser and generator")
class TemplatePatternGeneratorTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String HNB =
      "LKR 2,450.00 debited to Ac No:12XXXXXX34 on 27/09/26 14:02:19 Reason:KEELLS SUPERMARKET NUGEGODA Bal:";

  private static List<TemplateSampleAnalyser.Annotation> hnbAnnotations() {
    return List.of(
        new TemplateSampleAnalyser.Annotation(4, 12, "AMOUNT", null, null, null, null),
        new TemplateSampleAnalyser.Annotation(13, 20, "DIRECTION", "DEBIT", List.of("credited"), null, null),
        new TemplateSampleAnalyser.Annotation(30, 40, "IGNORE", null, null, null, "MASKED_ACCOUNT"),
        new TemplateSampleAnalyser.Annotation(44, 52, "DATE", null, null, "dd/MM/yy", null),
        new TemplateSampleAnalyser.Annotation(53, 61, "TIME", null, null, "HH:mm:ss", null),
        new TemplateSampleAnalyser.Annotation(69, 96, "MERCHANT", null, null, null, null));
  }

  private static ObjectNode hnbSpec() {
    return TemplateSampleAnalyser.buildSpec(HNB, hnbAnnotations(), null, null, "ACCOUNT_TRANSACTION", "BANK", List.of("HNB"));
  }

  @Test
  @DisplayName("the documented HNB example: pattern, field map and template view")
  void documentedExample() {
    TemplatePatternGenerator.Result r = TemplatePatternGenerator.generate(hnbSpec());
    assertEquals(
        "^LKR (?<amount>[0-9][0-9,]*(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2}) (?<dir>debited|credited) to Ac No:[0-9]+X+[0-9]+ on "
            + "(?<date>[0-9]{2}/[0-9]{2}/[0-9]{2}) (?<time>[0-9]{2}:[0-9]{2}:[0-9]{2}) Reason:(?<merchant>.+?) Bal:",
        r.pattern());
    assertEquals("LKR {Amount} {Direction} to Ac No:{Ignore} on {Date} {Time} Reason:{Merchant} Bal:", r.templateView());
    assertTrue(TemplatePatternValidator.problems(r.pattern()).isEmpty());
    assertTrue(TemplateFieldMap.check(r.fieldMap(), TemplatePatternValidator.groupNames(r.pattern())).ok());
    Map<String, String> v = TemplateFieldMap.read(Pattern.compile(r.pattern()), r.fieldMap(), HNB, LocalDate.now());
    assertEquals("2450.00", v.get("amount"));
    assertEquals("DEBIT", v.get("direction"));
    assertEquals("KEELLS SUPERMARKET NUGEGODA", v.get("merchant"));
    assertEquals("2026-09-27", v.get("txnDate"));
  }

  @Test
  @DisplayName("generation is deterministic and the spec holds no sample values")
  void deterministicAndPrivate() {
    assertEquals(TemplatePatternGenerator.generate(hnbSpec()).pattern(), TemplatePatternGenerator.generate(hnbSpec()).pattern());
    String spec = hnbSpec().toString();
    for (String value : List.of("2,450.00", "KEELLS", "12XXXXXX34", "27/09/26", "14:02:19")) {
      assertTrue(!spec.contains(value), value + " leaked into " + spec);
    }
  }

  @Test
  @DisplayName("required fields (E.020) and literal PII (E.019)")
  void refusals() {
    ObjectNode noAmount = hnbSpec();
    ((com.fasterxml.jackson.databind.node.ArrayNode) noAmount.get("segments")).remove(1);
    TemplatePatternGenerator.SpecException e =
        assertThrows(TemplatePatternGenerator.SpecException.class, () -> TemplatePatternGenerator.generate(noAmount));
    assertTrue(!e.literalPii());
    ObjectNode digits = hnbSpec();
    ((ObjectNode) digits.get("segments").get(0)).put("literal", "Card 41111111 ");
    assertTrue(assertThrows(TemplatePatternGenerator.SpecException.class, () -> TemplatePatternGenerator.generate(digits)).literalPii());
    assertThrows(
        TemplateSampleAnalyser.AnnotationException.class,
        () ->
            TemplateSampleAnalyser.buildSpec(
                HNB,
                List.of(new TemplateSampleAnalyser.Annotation(30, 40, "TAIL", null, null, null, null)),
                null, null, "X_Y", "BANK", List.of("HNB")));
  }

  @Test
  @DisplayName("every seed format: the analyser's suggestions give a portable template that reads the sample like the seed")
  void seedFormats() throws Exception {
    RedactedSampleSet set = new RedactedSampleSet(JSON);
    Set<String> seen = new HashSet<>();
    int checked = 0;
    for (Sample s : set.samples()) {
      if (!seen.add(s.bankCode() + s.messageType())) {
        continue;
      }
      TemplateSampleAnalyser.Analysis a = TemplateSampleAnalyser.analyse(s.sms());
      List<TemplateSampleAnalyser.Annotation> ann = new ArrayList<>();
      boolean direction = false;
      for (TemplateSampleAnalyser.Suggestion x : a.suggestions()) {
        direction |= "DIRECTION".equals(x.field());
        ann.add(new TemplateSampleAnalyser.Annotation(x.start(), x.end(), x.field(), x.direction(), x.counterpartWords(), x.format(), x.ignoreKind()));
      }
      if (ann.stream().noneMatch(x -> "MERCHANT".equals(x.field()))) {
        // Nothing to suggest (e.g. the sender's name in brackets): mark it as an admin would.
        String m = expectedMerchant(s.sms());
        int at = m == null ? -1 : s.sms().indexOf(m);
        assertTrue(at >= 0, s.bankCode() + " " + s.messageType() + ": no merchant suggested or known");
        ann.removeIf(x -> x.start() < at + m.length() && at < x.end());
        ann.add(new TemplateSampleAnalyser.Annotation(at, at + m.length(), "MERCHANT", null, null, null, null));
      }
      JsonNode fixed = JSON.createObjectNode().put("currency", "LKR").put("direction", direction ? null : "DEBIT");
      ObjectNode spec = TemplateSampleAnalyser.buildSpec(s.sms(), ann, fixed, null, "ACCOUNT_TRANSACTION", "BANK", List.of("X"));
      TemplatePatternGenerator.Result r = TemplatePatternGenerator.generate(spec);
      assertTrue(TemplatePatternValidator.problems(r.pattern()).isEmpty(), r.pattern());
      Map<String, String> v = TemplateFieldMap.read(Pattern.compile(r.pattern()), r.fieldMap(), s.sms(), LocalDate.now());
      assertNotNull(v, s.bankCode() + " " + s.messageType() + " not read by " + r.pattern());
      checked++;
    }
    assertEquals(19, checked);
  }

  /** The hand-checked merchant of a stored sample. */
  private static String expectedMerchant(String sms) throws java.io.IOException {
    try (java.io.InputStream in = TemplatePatternGeneratorTest.class.getResourceAsStream("/bank-sms/redacted-samples.json")) {
      for (JsonNode n : JSON.readTree(in).path("samples")) {
        if (sms.equals(n.path("sms").asText())) {
          return n.path("expected").path("merchant").asText(null);
        }
      }
    }
    return null;
  }

  @Test
  @DisplayName("OTP / PIN wording: suggestions are never needed, the sample is refused upstream")
  void piiWarnings() {
    List<String> kinds =
        TemplateSampleAnalyser.analyse("Dear John, LKR 1,000.00 paid to 123456789012 contact me@mail.com").piiWarnings().stream()
            .map(TemplateSampleAnalyser.PiiWarning::kind)
            .sorted()
            .toList();
    assertEquals(List.of("EMAIL", "LONG_NUMBER", "NAME"), kinds);
  }

  @Test
  @DisplayName("\"Rs\" marked as the currency is read as LKR (BOC POS/ATM, as an admin marks it)")
  void rupeeSignMarkedAsCurrency() {
    String sms =
        "POS/ATM Transaction Rs 1234.56 From A/C No XXXXXXXXXX123. Balance available Rs 1234.56 - Thank you for banking with BOC";
    int rs = sms.indexOf("Rs");
    int amount = sms.indexOf("1234.56");
    int account = sms.indexOf("XXXXXXXXXX123");
    int balance = sms.lastIndexOf("1234.56");
    List<TemplateSampleAnalyser.Annotation> marks =
        List.of(
            new TemplateSampleAnalyser.Annotation(0, 3, "MERCHANT", null, null, null, null),
            new TemplateSampleAnalyser.Annotation(rs, rs + 2, "CURRENCY", null, null, null, null),
            new TemplateSampleAnalyser.Annotation(amount, amount + 7, "AMOUNT", null, null, null, null),
            new TemplateSampleAnalyser.Annotation(account, account + 13, "IGNORE", null, null, null, "TEXT"),
            new TemplateSampleAnalyser.Annotation(balance, balance + 7, "IGNORE", null, null, null, "TEXT"));
    ObjectNode fixed = JSON.createObjectNode().putNull("currency").put("direction", "DEBIT").put("txnDateSource", "SMS_RECEIVED_DATE");
    ObjectNode options = JSON.createObjectNode().put("flexibleSpacing", true).put("trailing", "ENDS").put("generaliseNumbers", true);
    ObjectNode spec = TemplateSampleAnalyser.buildSpec(sms, marks, fixed, options, "CARD_PURCHASE", "BANK", List.of("BOC"));
    TemplatePatternGenerator.Result r = TemplatePatternGenerator.generate(spec);
    Map<String, String> v = TemplateFieldMap.read(Pattern.compile(r.pattern()), r.fieldMap(), sms, LocalDate.now());
    assertNotNull(v, r.pattern());
    assertEquals("1234.56", v.get("amount"));
    assertEquals("LKR", v.get("currency"));
    assertEquals("DEBIT", v.get("direction"));
    assertEquals("POS", v.get("merchant"));
    // Another amount and balance on the same format.
    assertNotNull(TemplateFieldMap.read(Pattern.compile(r.pattern()), r.fieldMap(),
        sms.replace("1234.56 From", "6879.14 From").replace("Rs 1234.56 -", "Rs 81.86 -"), LocalDate.now()));
  }
}
