package com.moneyreserve.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.moneyreserve.dto.ClientComplianceEvent;
import com.moneyreserve.dto.ComplianceEventBatchRequest;
import com.moneyreserve.dto.FormatSample;
import com.moneyreserve.dto.FormatSampleBatchRequest;
import com.moneyreserve.dto.IngestBatchRequest;
import com.moneyreserve.dto.IngestMessage;
import com.moneyreserve.dto.UnmatchedSmsStat;
import com.moneyreserve.dto.UnmatchedStatsRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

/**
 * The architecture test (BI-33). It exists to fail the build, not to describe the design.
 *
 * <p>Three of this feature's compliance claims are claims about shape rather than behaviour, which
 * means no amount of testing the code can establish them and a single careless field can quietly
 * undo them:
 *
 * <ol>
 *   <li><b>No free-text message field on any Insights DTO.</b> This is the third OTP control, and
 *       the only one that cannot fail at runtime: if there is no field capable of carrying a
 *       message body, then both filters missing an OTP still cannot bring it here.
 *   <li><b>The ingest contract carries only the fields it was reviewed with.</b> Compliance signed
 *       off a named list. A new String on {@link IngestMessage} — however well meant — widens what
 *       a handset may send, and should cost a conversation rather than a commit.
 *   <li><b>No request body names its own owner.</b> The caller is the authenticated principal and
 *       nothing else. A request that could carry a user identifier is a request that could write
 *       into somebody else's ledger (an insecure direct object reference).
 * </ol>
 *
 * <p>Written with reflection rather than ArchUnit deliberately: adding a test-only dependency to
 * assert a compliance control is a dependency that can be removed to make a build pass.
 */
class InsightsDtoArchitectureTest {

  private static final String DTO_PACKAGE = "com.moneyreserve.dto";

  /**
   * Field names that would mean message content.
   *
   * <p>Matched against the lower-cased field name as whole words or well-known compounds. Note what
   * is <em>not</em> here: {@code description} is a note the customer typed on a manual entry and is
   * part of the published R1 contract, and {@code messages} on {@link IngestBatchRequest} is a list
   * of structured messages, not a body.
   */
  private static final Pattern FREE_TEXT =
      Pattern.compile(
          "^(raw|rawmessage|rawbody|rawsms|rawtext|body|messagebody|smsbody|smstext|"
              + "text|fulltext|content|messagecontent|sms|smsmessage|message|payload|"
              + "originalmessage|messagetext)$");

  /** Field names that would let a caller name an owner other than themselves. */
  private static final Pattern OWNER_IDENTIFIER =
      Pattern.compile("^(userid|customerid|accountholderid|ownerid|subjectid)$");

  /**
   * Every field the ingest contract was reviewed with.
   *
   * <p>Adding to this set is the conscious act. Failing to add to it is the accident this test
   * catches.
   */
  private static final Set<String> INGEST_MESSAGE_FIELDS =
      Set.of(
          "idempotencyKey",
          "bankCode",
          "messageType",
          "parsedAt",
          "amount",
          "currency",
          "direction",
          "merchant",
          "txnDate",
          "instrumentType",
          "maskedTail",
          "parserTemplateId",
          "parseConfidence");

  /**
   * The batch envelope's reviewed fields.
   *
   * <p>{@code platform} was added in R6 and is a deliberate widening of a contract this test exists
   * to hold fixed. It is recorded here rather than waved through: it is constrained to
   * {@code ANDROID|IOS} by {@code @Pattern}, so it cannot carry message content, and it exists
   * because R4 recorded every ingested transaction as Android — which would have made R6's demo,
   * an iOS handset forwarding a bank message end to end, file iOS spend as Android spend. It
   * authorises nothing and is not a security boundary; a client can assert either value.
   */
  private static final Set<String> INGEST_BATCH_FIELDS =
      Set.of("deviceId", "templateVersion", "messages", "platform");

  /**
   * The BI-36 device compliance contract's reviewed fields.
   *
   * <p>Pinned for the same reason the ingest contract is, and with more at stake: this one writes
   * into {@code bi_compliance_event}, which is never purged — not on consent withdrawal and not on
   * erasure. A field added here is a field in a permanent record.
   *
   * <p>{@code clientRef} is the one client-controlled string, and it is the reason this set exists.
   * It is bounded to an identifier shape by {@code @Pattern} and hashed before storage, so it
   * cannot become the free-text column this whole contract is designed not to have. Removing
   * either of those protections should require removing the field from this set first.
   */
  private static final Set<String> COMPLIANCE_EVENT_FIELDS =
      Set.of("eventType", "detectedAt", "clientRef");

  private static final Set<String> COMPLIANCE_BATCH_FIELDS =
      Set.of("deviceId", "platform", "events");

  /**
   * The SMS format-discovery contracts' reviewed fields.
   *
   * <p>Pinned for the same reason as ingest: a field here is something every handset may send
   * about a message it could not read. {@code tokens} is the one part that describes a message,
   * and it is held to the skeleton grammar per token by {@code SkeletonGrammar}; a new field would
   * have no such guard.
   */
  private static final Set<String> FORMAT_SAMPLE_FIELDS = Set.of("clientRef", "senderId", "tokens");

  private static final Set<String> FORMAT_SAMPLE_BATCH_FIELDS =
      Set.of("deviceId", "platform", "vocabularyVersion", "samples");

  private static final Set<String> UNMATCHED_STAT_FIELDS = Set.of("senderId", "day", "count");

  private static final Set<String> UNMATCHED_STATS_FIELDS = Set.of("deviceId", "platform", "stats");

  @Test
  @DisplayName("No Insights DTO carries a field capable of holding a message body")
  void noFreeTextFieldOnAnyDto() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> type : dtoClasses()) {
      for (Field field : declaredFields(type)) {
        if (FREE_TEXT.matcher(field.getName().toLowerCase(Locale.ROOT)).matches()) {
          offenders.add(type.getSimpleName() + "." + field.getName());
        }
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "A message body cannot be allowed to reach this service through a DTO. "
            + "The lawful route for raw text is the consent-gated parser-improvement submission, "
            + "which writes encrypted to bi_raw_message with a TTL. Offending fields: "
            + offenders);
  }

  @Test
  @DisplayName("No DTO lets a caller name an owner other than the authenticated one")
  void noOwnerIdentifierOnAnyDto() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> type : dtoClasses()) {
      for (Field field : declaredFields(type)) {
        if (OWNER_IDENTIFIER.matcher(field.getName().toLowerCase(Locale.ROOT)).matches()) {
          offenders.add(type.getSimpleName() + "." + field.getName());
        }
      }
    }
    assertTrue(
        offenders.isEmpty(),
        "Ownership comes from the access token, never from the body. Offending fields: "
            + offenders);
  }

  @Test
  @DisplayName("The ingest contract has exactly the fields Compliance reviewed")
  void ingestContractIsClosed() {
    assertFieldsExactly(IngestMessage.class, INGEST_MESSAGE_FIELDS);
    assertFieldsExactly(IngestBatchRequest.class, INGEST_BATCH_FIELDS);
  }

  @Test
  @DisplayName("The device compliance contract has exactly the fields Compliance reviewed")
  void complianceContractIsClosed() {
    assertFieldsExactly(ClientComplianceEvent.class, COMPLIANCE_EVENT_FIELDS);
    assertFieldsExactly(ComplianceEventBatchRequest.class, COMPLIANCE_BATCH_FIELDS);
  }

  @Test
  @DisplayName("The SMS format-discovery contracts have exactly the fields Compliance reviewed")
  void formatDiscoveryContractIsClosed() {
    assertFieldsExactly(FormatSample.class, FORMAT_SAMPLE_FIELDS);
    assertFieldsExactly(FormatSampleBatchRequest.class, FORMAT_SAMPLE_BATCH_FIELDS);
    assertFieldsExactly(UnmatchedSmsStat.class, UNMATCHED_STAT_FIELDS);
    assertFieldsExactly(UnmatchedStatsRequest.class, UNMATCHED_STATS_FIELDS);
  }

  @Test
  @DisplayName("Every String on the ingest and compliance contracts is bounded by a constraint")
  void ingestStringsAreBounded() {
    List<String> unbounded = new ArrayList<>();
    for (Class<?> type :
        List.of(
            IngestMessage.class,
            IngestBatchRequest.class,
            ClientComplianceEvent.class,
            ComplianceEventBatchRequest.class,
            FormatSample.class,
            FormatSampleBatchRequest.class,
            UnmatchedSmsStat.class,
            UnmatchedStatsRequest.class)) {
      for (Field field : declaredFields(type)) {
        if (!String.class.equals(field.getType())) {
          continue;
        }
        boolean bounded =
            field.isAnnotationPresent(jakarta.validation.constraints.Size.class)
                || field.isAnnotationPresent(jakarta.validation.constraints.Pattern.class);
        if (!bounded) {
          unbounded.add(type.getSimpleName() + "." + field.getName());
        }
      }
    }
    // The allowlist above records that a field name was reviewed. This records that it is still
    // incapable of carrying a message: an unbounded String on this contract is a free-text field
    // by another name, whoever approved its name. Both halves are needed -- a reviewed field that
    // later loses its @Size would otherwise pass silently.
    assertTrue(
        unbounded.isEmpty(),
        "Every String on these contracts must carry @Size or @Pattern. Unbounded: "
            + unbounded);
  }

  @Test
  @DisplayName("The scan actually found the DTOs, so a green run means something")
  void scanIsNotEmpty() {
    // Without this, a package rename would turn all three tests above into vacuous passes -- the
    // most dangerous failure mode a test like this has.
    List<Class<?>> found = dtoClasses();
    assertFalse(found.isEmpty(), "No DTO classes were scanned; the package name is wrong.");
    assertTrue(
        found.size() >= 20,
        "Only " + found.size() + " DTOs were scanned, which is fewer than this service has.");
  }

  // ---------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------

  private void assertFieldsExactly(Class<?> type, Set<String> expected) {
    List<String> actual = new ArrayList<>();
    for (Field field : declaredFields(type)) {
      actual.add(field.getName());
    }
    for (String name : actual) {
      if (!expected.contains(name)) {
        fail(
            type.getSimpleName()
                + " has a new field '"
                + name
                + "'. The ingest contract is fixed by compliance review: widen"
                + " the pinned field set in this test only together with that review.");
      }
    }
    for (String name : expected) {
      assertTrue(
          actual.contains(name),
          type.getSimpleName() + " no longer has '" + name + "', which the device contract needs.");
    }
  }

  /** Instance fields only — synthetic and static members are compiler and Lombok business. */
  private List<Field> declaredFields(Class<?> type) {
    List<Field> fields = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
        fields.add(field);
      }
    }
    return fields;
  }

  private List<Class<?>> dtoClasses() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
    List<Class<?>> types = new ArrayList<>();
    for (BeanDefinition definition : scanner.findCandidateComponents(DTO_PACKAGE)) {
      try {
        types.add(Class.forName(definition.getBeanClassName()));
      } catch (ClassNotFoundException ex) {
        fail("Scanned a DTO that could not be loaded: " + definition.getBeanClassName());
      }
    }
    return types;
  }
}
