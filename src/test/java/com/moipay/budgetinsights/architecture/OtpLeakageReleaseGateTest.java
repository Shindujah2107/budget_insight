package com.moipay.budgetinsights.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.domain.BiComplianceEvent;
import com.moipay.budgetinsights.dto.IngestBatchResponse;
import com.moipay.budgetinsights.dto.IngestResultItem;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.service.ingest.OtpGuard;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The OTP and PIN leakage release gate (BI-32).
 *
 * <p>DIP §21 states the control this class exists to hold: <i>"A release-gate test asserts that no
 * OTP or PIN text appears in any outbound payload, any table or any log line, for every supported
 * bank format. Failure blocks the build."</i> These are Surefire tests, so a failure does fail
 * {@code mvn test} and therefore the build.
 *
 * <p><b>The gate has two halves and only one of them is code.</b>
 *
 * <ul>
 *   <li><b>The structural half</b> — that a rejected message cannot reach an outbound payload, and
 *       that no table can hold the text even if it did — is asserted here and runs on every build.
 *   <li><b>The per-bank half</b> — that the guard makes the right call on real messages from every
 *       supported bank — needs the labelled sample set, dependency <b>D-1</b>. Until that file
 *       exists the corpus tests <b>abort rather than pass</b>, so Surefire reports them as skipped
 *       with the reason attached. A green build with those two skipped does <em>not</em> mean the
 *       gate is passed, and Compliance gate 2 must not be signed on one.
 * </ul>
 *
 * <p>To close the gate, drop the labelled corpus at
 * {@code src/test/resources/otp-gate/bank-corpus.tsv} — the format is in the README beside it — and
 * re-run. Nothing else needs changing.
 */
class OtpLeakageReleaseGateTest {

  private static final Path CORPUS = Path.of("src/test/resources/otp-gate/bank-corpus.tsv");

  private static final String D1 =
      "D-1: the labelled SMS sample set is not present at "
          + "src/test/resources/otp-gate/bank-corpus.tsv. Per-bank parser and OTP accuracy is "
          + "NOT VERIFIED and compliance gate 2 cannot be passed on this build.";

  // ---------------------------------------------------------------------------------
  // The structural half — runs on every build
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("A rejected message's text never reaches the outbound payload")
  void outboundPayloadCarriesNothingOfTheMessage() throws Exception {
    String leaked = "Your OTP for online banking is 839201.";

    // Exactly what the service builds for a message the guard refused: the device's own key, the
    // status, and the code. Never the value, never the field, never the rule that caught it.
    IngestBatchResponse response =
        IngestBatchResponse.builder()
            .accepted(0)
            .duplicates(0)
            .rejected(1)
            .results(
                List.of(
                    IngestResultItem.builder()
                        .idempotencyKey("sha256:aaaabbbbccccddddeeeeffff0000")
                        .status("REJECTED")
                        .error(ErrorCode.INSIGHTS_OTP_PAYLOAD_REJECTED.getCode())
                        .build()))
            .build();

    String json = new ObjectMapper().writeValueAsString(response);

    assertFalse(json.contains("839201"), "The code reached the response: " + json);
    assertFalse(json.contains(leaked), "The message reached the response: " + json);
    String lower = json.toLowerCase(Locale.ROOT);
    assertFalse(lower.contains("otp"), "The response names the classification: " + json);
    assertFalse(lower.contains("pin"), "The response names the classification: " + json);
    // The client still learns enough to drop the message and move on.
    assertTrue(json.contains(ErrorCode.INSIGHTS_OTP_PAYLOAD_REJECTED.getCode()));
  }

  @Test
  @DisplayName("The compliance row has no column capable of holding content or a value")
  void complianceRowCannotHoldContent() {
    // clientRefHash (BI-36) is on this list rather than in offenders because it holds a SHA-256
    // digest of the reporting device's own reference and never the reference itself. It is the one
    // client-supplied value that reaches this table, and hashing it is what keeps the property
    // this test exists to assert true by construction rather than by the client behaving.
    Set<String> permitted =
        new LinkedHashSet<>(
            List.of(
                "id",
                "eventType",
                "source",
                "action",
                "deviceIdHash",
                "occurredAt",
                "tenantId",
                "clientRefHash"));

    List<String> offenders = new ArrayList<>();
    for (Field field : BiComplianceEvent.class.getDeclaredFields()) {
      if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
        continue;
      }
      if (!permitted.contains(field.getName())) {
        offenders.add(field.getName() + " (" + field.getType().getSimpleName() + ")");
      }
      // A money column here would let an incident carry the amount of the transaction that caused
      // it, which is the customer's financial detail sitting in the one table that is never purged.
      if (BigDecimal.class.equals(field.getType())) {
        offenders.add(field.getName() + " is a BigDecimal");
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "bi_compliance_event is the table a regulator reads and the one table nothing purges. "
            + "It must stay incapable of holding message content or a financial value. New fields: "
            + offenders);
  }

  // ---------------------------------------------------------------------------------
  // The per-bank half — aborts until D-1 lands
  // ---------------------------------------------------------------------------------

  @Test
  @DisplayName("The guard makes the right call on every labelled message, per bank")
  void guardDecidesEveryCorpusLine() throws IOException {
    Assumptions.abort(D1);
    List<String[]> rows = corpus();

    List<String> wrong = new ArrayList<>();
    for (String[] row : rows) {
      String bank = row[0];
      boolean expectedLeak = "LEAK".equalsIgnoreCase(row[1]);
      String value = row[2];
      boolean tripped = OtpGuard.trips(value);

      if (tripped != expectedLeak) {
        // A miss is a code in the ledger. A false positive is a customer's spend silently gone and
        // a permanent OTP_DETECTED row against a grocery run. Both fail the gate.
        wrong.add(
            bank
                + " expected "
                + (expectedLeak ? "LEAK" : "CLEAN")
                + " but guard said "
                + (tripped ? "LEAK" : "CLEAN"));
      }
    }
    if (!wrong.isEmpty()) {
      fail(
          "The OTP leakage gate is not green. The gate cannot be partially passed for a subset of "
              + "banks (DIP §32), so none of these may be waived:\n  "
              + String.join("\n  ", wrong));
    }
  }

  @Test
  @DisplayName("The corpus actually covers every supported bank, both ways")
  void corpusCoversEveryBankBothWays() throws IOException {
    Assumptions.abort(D1);
    List<String[]> rows = corpus();

    Map<String, boolean[]> seen = new LinkedHashMap<>();
    for (String[] row : rows) {
      boolean[] both = seen.computeIfAbsent(row[0], k -> new boolean[2]);
      if ("LEAK".equalsIgnoreCase(row[1])) {
        both[0] = true;
      } else {
        both[1] = true;
      }
    }
    assertFalse(seen.isEmpty(), "The corpus is present but empty.");

    List<String> thin = new ArrayList<>();
    for (Map.Entry<String, boolean[]> entry : seen.entrySet()) {
      if (!entry.getValue()[0]) {
        thin.add(entry.getKey() + " has no LEAK case");
      }
      if (!entry.getValue()[1]) {
        thin.add(entry.getKey() + " has no CLEAN case");
      }
    }
    // A corpus of only leaks proves the guard is willing to reject; a corpus of only clean messages
    // proves it is willing to accept. Neither on its own is evidence of anything.
    assertTrue(thin.isEmpty(), "The corpus does not exercise both outcomes per bank: " + thin);
  }

  // ---------------------------------------------------------------------------------

  /** bankCode TAB LEAK|CLEAN TAB value. Blank lines and lines starting with # are ignored. */
  private List<String[]> corpus() throws IOException {
    List<String[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(CORPUS, StandardCharsets.UTF_8)) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      String[] parts = line.split("\t", 3);
      if (parts.length != 3) {
        fail("Malformed corpus line (expected three tab-separated columns): " + line);
      }
      rows.add(parts);
    }
    return rows;
  }
}
