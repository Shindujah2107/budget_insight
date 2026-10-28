package com.moneyreserve.service.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * BI-35 — the calculation golden files.
 *
 * <p>Every figure on the Insights screen comes out of these two classes, and none of them throws
 * when it is wrong. A budget ring that is off by a day, a utilisation that rounds the wrong way, a
 * share column that adds up to 99.9 — each is a number the customer reads and believes, and each
 * would pass every other test in this suite.
 *
 * <p><b>The expected values were worked out by hand, not captured from a run.</b> A golden file
 * generated from the implementation pins whatever the code does today, bug and all, and turns the
 * next regression into a file somebody re-records. These were derived from the rules, so changing
 * the arithmetic means changing a number a human wrote down on purpose.
 *
 * <p>Amounts are compared as strings because scale is part of the contract. A utilisation of
 * {@code 51.7} and one of {@code 51.70} are the same number and a different answer, and the client
 * renders what it is given.
 */
@DisplayName("Golden files — budget arithmetic and share allocation")
class GoldenFileCalculationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode load(String name) {
    try (InputStream in = GoldenFileCalculationTest.class.getResourceAsStream("/golden/" + name)) {
      if (in == null) {
        throw new IllegalStateException("Golden file /golden/" + name + " is not on the classpath");
      }
      return MAPPER.readTree(in);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not read the golden file " + name, ex);
    }
  }

  private static final JsonNode MATH = load("budget-math.json");
  private static final JsonNode SHARES = load("share-allocation.json");

  private static BigDecimal money(JsonNode node) {
    return node == null || node.isNull() ? null : new BigDecimal(node.asText());
  }

  private static Stream<JsonNode> cases(JsonNode file, String section) {
    JsonNode node = file.get(section);
    assertTrue(node != null && node.isArray() && !node.isEmpty(), "No '" + section + "' cases");
    List<JsonNode> out = new ArrayList<>();
    node.forEach(out::add);
    return out.stream();
  }

  // ---------------------------------------------------------------------------------
  // Budget arithmetic
  // ---------------------------------------------------------------------------------

  @TestFactory
  @DisplayName("days elapsed, days remaining and the days text")
  Stream<DynamicTest> days() {
    return cases(MATH, "days")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () -> {
                      InsightsPeriod period =
                          InsightsPeriod.resolve(
                              LocalDate.parse(c.get("periodStart").asText()),
                              LocalDate.parse(c.get("periodEnd").asText()),
                              LocalDate.parse(c.get("today").asText()));
                      LocalDate today = LocalDate.parse(c.get("today").asText());

                      int elapsed = BudgetMath.daysElapsed(period, today);
                      int remaining = BudgetMath.daysRemaining(period, today);

                      assertEquals(c.get("elapsed").asInt(), elapsed, "days elapsed");
                      assertEquals(c.get("remaining").asInt(), remaining, "days remaining");
                      assertEquals(
                          c.get("text").asText(),
                          BudgetMath.daysText(elapsed, remaining),
                          "days text");
                    }));
  }

  @TestFactory
  @DisplayName("utilisation percentage")
  Stream<DynamicTest> utilisation() {
    return cases(MATH, "utilisation")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () ->
                        assertEquals(
                            c.get("pct").asText(),
                            BudgetMath.utilisationPct(money(c.get("spent")), money(c.get("limit")))
                                .toPlainString())));
  }

  @TestFactory
  @DisplayName("spend per day")
  Stream<DynamicTest> perDay() {
    return cases(MATH, "perDay")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () ->
                        assertEquals(
                            c.get("perDay").asText(),
                            BudgetMath.perDay(money(c.get("amount")), c.get("days").asInt())
                                .toPlainString())));
  }

  @TestFactory
  @DisplayName("pace variance")
  Stream<DynamicTest> variance() {
    return cases(MATH, "variance")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () ->
                        assertEquals(
                            c.get("variance").asText(),
                            BudgetMath.variance(
                                money(c.get("average")),
                                money(c.get("ideal")),
                                c.get("tolerancePct").asInt()))));
  }

  @TestFactory
  @DisplayName("category cap state")
  Stream<DynamicTest> capState() {
    return cases(MATH, "capState")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () -> {
                      JsonNode threshold = c.get("thresholdPct");
                      CapState actual =
                          BudgetMath.capState(
                              money(c.get("spent")),
                              money(c.get("limit")),
                              threshold.isNull() ? null : (short) threshold.asInt(),
                              (short) c.get("defaultThresholdPct").asInt());

                      if (c.get("state").isNull()) {
                        assertNull(actual, "expected no cap state");
                      } else {
                        assertEquals(CapState.valueOf(c.get("state").asText()), actual);
                      }
                    }));
  }

  @TestFactory
  @DisplayName("overall status")
  Stream<DynamicTest> status() {
    return cases(MATH, "status")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () ->
                        assertEquals(
                            c.get("status").asText(),
                            BudgetMath.status(
                                money(c.get("utilisationPct")), c.get("variance").asText()))));
  }

  @TestFactory
  @DisplayName("trailing median")
  Stream<DynamicTest> median() {
    return cases(MATH, "median")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () -> {
                      List<BigDecimal> values = new ArrayList<>();
                      c.get("values").forEach(v -> values.add(new BigDecimal(v.asText())));
                      assertEquals(
                          c.get("median").asText(), BudgetMath.median(values).toPlainString());
                    }));
  }

  // ---------------------------------------------------------------------------------
  // Share allocation
  // ---------------------------------------------------------------------------------

  @TestFactory
  @DisplayName("shares that arithmetic alone determines")
  Stream<DynamicTest> exactShares() {
    return cases(SHARES, "exact")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () -> {
                      List<Double> expected = new ArrayList<>();
                      c.get("shares").forEach(v -> expected.add(v.asDouble()));
                      assertEquals(expected, ShareAllocator.allocate(amounts(c)));
                    }));
  }

  @TestFactory
  @DisplayName("awkward splits still add up to exactly 100")
  Stream<DynamicTest> sharesSumToOneHundred() {
    return cases(SHARES, "sums")
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.get("name").asText(),
                    () -> {
                      // In tenths, because that is the unit the allocator works in and because
                      // summing doubles to compare against 100.0 is the bug this guards against
                      // dressed up as the test for it.
                      int total = 0;
                      for (int tenths : ShareAllocator.allocateTenths(amounts(c))) {
                        assertTrue(tenths >= 0, "a negative share was allocated");
                        total += tenths;
                      }
                      assertEquals(
                          ShareAllocator.TOTAL_TENTHS,
                          total,
                          "the shares do not add up to exactly 100");
                    }));
  }

  @Test
  @DisplayName("both golden files were actually found and are not empty")
  void theGoldenFilesAreLoaded() {
    // Without this, a renamed resource directory turns every factory above into zero tests and a
    // green build -- the one failure mode a data-driven suite has that a hand-written one does not.
    assertFalse(MATH.isEmpty(), "budget-math.json is empty");
    assertFalse(SHARES.isEmpty(), "share-allocation.json is empty");
    assertTrue(MATH.get("days").size() >= 9, "the days cases have been thinned out");
    assertTrue(SHARES.get("sums").size() >= 5, "the share cases have been thinned out");
  }

  private static List<BigDecimal> amounts(JsonNode c) {
    List<BigDecimal> out = new ArrayList<>();
    c.get("amounts").forEach(v -> out.add(new BigDecimal(v.asText())));
    return out;
  }
}
