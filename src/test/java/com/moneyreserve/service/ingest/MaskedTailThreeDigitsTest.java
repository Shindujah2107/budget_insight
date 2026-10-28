package com.moneyreserve.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moneyreserve.exception.BudgetInsightsException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1.14.0: some banks show only the last three digits of the account or card (e.g. BOC
 * "A/C No XXXXXXXXXX463"). Three or four digits are a masked tail; anything else is refused.
 */
class MaskedTailThreeDigitsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @DisplayName("ingest: a 3- or 4-digit tail is accepted, anything else is refused, never shortened")
  void normaliserAcceptsThreeOrFourDigits() {
    assertEquals("463", PayloadNormaliser.maskedTail("463"));
    assertEquals("4821", PayloadNormaliser.maskedTail(" 4821 "));
    assertEquals(null, PayloadNormaliser.maskedTail("  "));
    for (String bad : List.of("46", "48210", "482100", "4a3", "***463")) {
      assertThrows(BudgetInsightsException.class, () -> PayloadNormaliser.maskedTail(bad), bad);
    }
  }

  @Test
  @DisplayName("analyser: the 3 visible digits of a masked number are suggested as the tail")
  void analyserSuggestsThreeDigitTail() {
    String sms = "POS/ATM Transaction Rs 8030.00 From A/C No XXXXXXXXXX463. Balance available Rs 1.00";
    TemplateSampleAnalyser.Analysis a = TemplateSampleAnalyser.analyse(sms);
    assertTrue(
        a.suggestions().stream()
            .anyMatch(s -> "TAIL".equals(s.field()) && "463".equals(sms.substring(s.start(), s.end()))),
        a.suggestions().toString());
  }

  @Test
  @DisplayName("analyser: a 4-digit tail always wins; 3 digits is only a fallback")
  void fourDigitTailStillPreferred() {
    String sms = "Txn #123 on card ending 4821 for LKR 500.00 at KEELLS on 01/10/26";
    assertEquals(List.of("4821"), tails(sms));
    assertEquals(List.of(), tails("Ref #482 LKR 1,200.00 debited at ODEL on 02/10/26"));
  }

  @Test
  @DisplayName("analyser: a branch prefix ('A/C 280-2001****68') is not a tail")
  void branchPrefixIsNotATail() {
    String sms =
        "Dear Sir/Madam, Your A/C 280-2001****68 has been debited by Rs. 190.00 (POS @20:30 13/04/2026 at X).";
    assertEquals(List.of(), tails(sms));
  }

  private static List<String> tails(String sms) {
    return TemplateSampleAnalyser.analyse(sms).suggestions().stream()
        .filter(s -> "TAIL".equals(s.field()))
        .map(s -> sms.substring(s.start(), s.end()))
        .toList();
  }

  @Test
  @DisplayName("builder: a 3-digit tail gives digits=3 and a pattern that reads it; 2 digits is refused")
  void builderCapturesThreeDigits() throws Exception {
    String sms = "BOC Debit Card ending 463 used for LKR 12,800.00 at ABANS COLOMBO 03 on 08/10/26.";
    List<TemplateSampleAnalyser.Annotation> ann = new ArrayList<>();
    for (TemplateSampleAnalyser.Suggestion s : TemplateSampleAnalyser.analyse(sms).suggestions()) {
      String field = "DIRECTION".equals(s.field()) ? "IGNORE" : s.field();
      ann.add(new TemplateSampleAnalyser.Annotation(s.start(), s.end(), field, null, null, s.format(), null));
    }
    JsonNode fixed = JSON.createObjectNode().put("currency", "LKR").put("direction", "DEBIT");
    ObjectNode spec =
        TemplateSampleAnalyser.buildSpec(sms, ann, fixed, null, "CARD_TRANSACTION", "CARD", List.of("BOC"));
    TemplatePatternGenerator.Result r = TemplatePatternGenerator.generate(spec);
    assertEquals(3, r.fieldMap().path("maskedTail").path("digits").asInt());
    assertTrue(r.pattern().contains("(?<tail>[0-9]{3})"), r.pattern());
    Map<String, String> v = TemplateFieldMap.read(Pattern.compile(r.pattern()), r.fieldMap(), sms, LocalDate.now());
    assertNotNull(v);
    assertEquals("463", v.get("maskedTail"));

    int start = sms.indexOf("463");
    List<TemplateSampleAnalyser.Annotation> twoDigits = new ArrayList<>(ann);
    twoDigits.removeIf(x -> "TAIL".equals(x.field()));
    twoDigits.add(new TemplateSampleAnalyser.Annotation(start + 1, start + 3, "TAIL", null, null, null, null));
    assertThrows(
        TemplateSampleAnalyser.AnnotationException.class,
        () -> TemplateSampleAnalyser.buildSpec(sms, twoDigits, fixed, null, "CARD_TRANSACTION", "CARD", List.of("BOC")));
  }

  @Test
  @DisplayName("builder: a spec saved before V1.14.0 (no digits) still captures four digits")
  void oldSpecsKeepFourDigits() throws Exception {
    ObjectNode seg = JSON.createObjectNode().put("field", "TAIL");
    assertEquals(4, TemplatePatternGenerator.tailDigits(seg));
    assertEquals(3, TemplatePatternGenerator.tailDigits(seg.put("digits", 3)));
  }
}
