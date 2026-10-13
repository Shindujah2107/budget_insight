package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.MatchType;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.dto.AdminMerchantRuleTest.CategoryRef;
import com.moipay.budgetinsights.dto.AdminTemplateCheck;
import com.moipay.budgetinsights.dto.AdminTemplateCheck.Extracted;
import com.moipay.budgetinsights.dto.AdminTemplateCheck.Highlight;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.dto.AdminValidation.Check;
import com.moipay.budgetinsights.dto.AdminValidation.Issue;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.service.calc.MerchantRuleMatcher;
import com.moipay.budgetinsights.service.calc.MessageTypeCategories;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import com.moipay.budgetinsights.service.ingest.RedactedSampleSet;
import com.moipay.budgetinsights.service.ingest.RedactedSampleSet.Sample;
import com.moipay.budgetinsights.service.ingest.TemplateFieldMap;
import com.moipay.budgetinsights.service.ingest.TemplatePatternValidator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The per-template checks shown while editing a template (T4 generate, T5 validate) and run again
 * on save: portable pattern, field map, samples matched, and regression against the stored
 * redacted samples with this template in place of the one it replaces.
 *
 * <p>Samples are only ever held in local variables: never stored, cached, logged or returned. Only
 * extracted values and character offsets leave this class.
 */
@Component
@RequiredArgsConstructor
public class AdminTemplateChecks {

  public static final int MAX_SAMPLES = 5;
  public static final int MAX_SAMPLE_LENGTH = 1_000;

  private static final Map<String, String> GROUP_FIELDS =
      Map.of(
          "amount", "AMOUNT",
          "dir", "DIRECTION",
          "merchant", "MERCHANT",
          "date", "DATE",
          "time", "TIME",
          "tail", "TAIL",
          "currency", "CURRENCY");

  private final AdminEffectiveView view;
  private final RedactedSampleSet stored;
  private final MerchantRuleMatcher matcher = new MerchantRuleMatcher();
  private final InsightsProperties properties;

  /** E.013 when the pattern is not portable, E.014 when the field map is not valid for it. */
  public void requireValid(String pattern, JsonNode fieldMap) {
    List<String> problems = TemplatePatternValidator.problems(pattern);
    if (!problems.isEmpty()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_PATTERN_INVALID,
          problems.get(0),
          problems.stream().map(p -> new AdminFieldError("pattern", p)).toList());
    }
    TemplateFieldMap.Check map =
        TemplateFieldMap.check(fieldMap, TemplatePatternValidator.groupNames(pattern));
    if (!map.ok()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_MAP_INVALID,
          map.errors().get(0),
          map.errors().stream().map(e -> new AdminFieldError("fieldMap", e)).toList());
    }
  }

  /** E.018 for an OTP / PIN sample or one over 1,000 characters. The sample is not echoed. */
  public static void requireAcceptableSamples(List<String> samples) {
    if (samples == null) {
      return;
    }
    if (samples.size() > MAX_SAMPLES) {
      throw sampleRejected("samples", "at most " + MAX_SAMPLES + " samples per call");
    }
    for (int i = 0; i < samples.size(); i++) {
      String s = samples.get(i);
      if (s == null || s.isBlank()) {
        throw sampleRejected("samples[" + i + "]", "is empty");
      }
      if (s.length() > MAX_SAMPLE_LENGTH) {
        throw sampleRejected("samples[" + i + "]", "is longer than 1,000 characters");
      }
      if (looksLikeOtp(s)) {
        throw sampleRejected(
            "samples[" + i + "]",
            "looks like an OTP or PIN message; those must never be templated");
      }
    }
  }

  private static final Pattern OTP_WORDING =
      Pattern.compile(
          "(?i)\\b(?:otps?|m?pin|one[- ]?time[- ]?(?:password|passcode|pin|code)|verification code"
              + "|security code|passcode|cvv2?)\\b");
  private static final Pattern CODE =
      Pattern.compile("(?<![0-9A-Za-z.,:/*\\-])[0-9]{4,8}(?![0-9A-Za-z,:/*\\-])(?!\\.[0-9])");

  /**
   * An OTP / PIN message: OTP or PIN wording together with a stand-alone 4–8 digit code. Bank
   * alerts that only carry a "do not share your OTP" footer have no such code (amounts, dates, times
   * and masked accounts are not codes) and are accepted.
   */
  static boolean looksLikeOtp(String sms) {
    return OTP_WORDING.matcher(sms).find() && CODE.matcher(sms).find();
  }

  static BudgetInsightsException sampleRejected(String field, String why) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_SAMPLE_REJECTED,
        "That sample cannot be used: " + why + ".",
        AdminFieldError.of(field, why));
  }

  /**
   * Runs the checks for a valid pattern and field map.
   *
   * @param replacing the template this one replaces (edit), or {@code null} for a new template
   */
  public AdminTemplateCheck run(
      String bankCode,
      String messageType,
      String pattern,
      JsonNode fieldMap,
      List<String> samples,
      UUID replacing) {
    List<Check> checks = new ArrayList<>();
    List<Issue> warnings = new ArrayList<>();
    List<String> problems = TemplatePatternValidator.problems(pattern);
    checks.add(
        new Check(
            "PATTERN_VALID",
            problems.isEmpty(),
            problems.isEmpty()
                ? "Pattern valid and portable (Java, Android, iOS)"
                : "Pattern is not portable: " + problems.get(0)));
    Set<String> groups = TemplatePatternValidator.groupNames(pattern);
    TemplateFieldMap.Check map = TemplateFieldMap.check(fieldMap, groups);
    checks.add(
        new Check(
            "FIELDS_MAPPED",
            map.ok(),
            map.ok() ? "Every field map group exists in the pattern" : map.errors().get(0)));
    map.warnings()
        .forEach(w -> warnings.add(new Issue("FIELD_MAP_WARNING", ConfigEntityType.TEMPLATE, replacing, w)));
    Pattern compiled = problems.isEmpty() ? Pattern.compile(pattern) : null;

    List<Extracted> extracted = new ArrayList<>();
    int matched = 0;
    List<String> given = samples == null ? List.of() : samples;
    for (int i = 0; i < given.size(); i++) {
      Extracted e = compiled == null || !map.ok() ? new Extracted(i, false, null, List.of(), null) : extract(i, compiled, fieldMap, given.get(i), messageType);
      extracted.add(e);
      if (e.matched()) {
        matched++;
      }
    }
    if (!given.isEmpty()) {
      checks.add(
          new Check(
              "SAMPLES_MATCHED",
              matched == given.size(),
              matched + " of " + given.size() + " sample" + (given.size() == 1 ? "" : "s") + " matched"));
    }
    if (compiled != null) {
      checks.add(regression(bankCode, messageType, compiled, replacing, warnings));
    }
    return new AdminTemplateCheck(checks, warnings, extracted);
  }

  private Extracted extract(int index, Pattern pattern, JsonNode fieldMap, String sms, String messageType) {
    Map<String, String> fields;
    try {
      fields = TemplateFieldMap.read(pattern, fieldMap, sms, LocalDate.now(properties.zoneId()));
    } catch (RuntimeException ex) {
      // A date the format cannot read, an amount that is not a number: matched, but not usable.
      fields = null;
    }
    if (fields == null) {
      return new Extracted(index, false, null, List.of(), null);
    }
    fields = ordered(fields);
    Matcher m = pattern.matcher(sms);
    List<Highlight> highlights = new ArrayList<>();
    if (m.find()) {
      for (String g : TemplatePatternValidator.groupNames(pattern.pattern())) {
        int start = m.start(g);
        if (start >= 0) {
          highlights.add(new Highlight(GROUP_FIELDS.getOrDefault(g, g.toUpperCase()), start, m.end(g)));
        }
      }
    }
    CategoryRef category = null;
    String merchant = fields.get("merchant");
    if (merchant != null && !merchant.isBlank()) {
      category = categoryFor(merchant, messageType);
    }
    boolean usable = fields.get("amount") != null && fields.get("direction") != null && fields.get("txnDate") != null;
    return new Extracted(index, usable, fields, highlights, category);
  }

  /**
   * The category the extracted merchant would get with the configuration as drafted (published
   * rules with pending changes applied), using the categoriser's matcher and order; OTHER when no
   * rule matches, {@code null} when there is no OTHER yet.
   */
  private CategoryRef categoryFor(String merchant, String messageType) {
    String needle = MerchantRuleMatcher.normalise(merchant);
    Map<UUID, Item<AdminCategoryState>> categories = AdminEffectiveView.byId(view.categories());
    // Layer 0 first, exactly as ingest: some message types are decided by type alone.
    Optional<String> byType =
        MessageTypeCategories.codeFor(properties.getCategorisation().getMessageTypeCategories(), messageType);
    if (byType.isPresent()) {
      Optional<CategoryRef> typed =
          categories.values().stream()
              .filter(c -> c.survives() && c.effective().active() && byType.get().equals(c.effective().code()))
              .findFirst()
              .map(c -> new CategoryRef(c.effective().code(), c.effective().nameEn()));
      if (typed.isPresent()) {
        return typed.get();
      }
    }
    List<Item<AdminMerchantRuleState>> live =
        view.merchantRules().stream()
            .filter(r -> r.survives() && r.effective().active())
            .filter(r -> {
              Item<AdminCategoryState> c = categories.get(r.effective().categoryId());
              return c != null && c.survives() && c.effective().active();
            })
            .toList();
    // Ranked exactly as the categoriser ranks them (level, specificity, priority, type, id).
    Optional<AdminMerchantRuleState> hit =
        matcher
            .ranked(
                live,
                r -> r.effective().priority(),
                r -> MatchType.valueOf(r.effective().matchType()),
                r -> r.effective().pattern(),
                needle,
                Comparator.comparing((Item<AdminMerchantRuleState> r) -> r.id()))
            .stream()
            .map(m -> m.rule().effective())
            .findFirst();
    if (hit.isPresent()) {
      AdminCategoryState c = categories.get(hit.get().categoryId()).effective();
      return new CategoryRef(c.code(), c.nameEn());
    }
    return categories.values().stream()
        .map(Item::effective)
        .filter(c -> AdminCategoryState.OTHER.equals(c.code()))
        .findFirst()
        .map(c -> new CategoryRef(c.code(), c.nameEn()))
        .orElse(null);
  }

  /**
   * Stored samples with this template in the live set: none may match two templates, none of the
   * never-read messages (OTP, marketing …) may match it, and this bank + type's stored samples
   * should still be read. Reported as warnings on a draft; they return as publish checks.
   */
  private Check regression(
      String bankCode, String messageType, Pattern candidate, UUID replacing, List<Issue> warnings) {
    List<Pattern> others = new ArrayList<>();
    for (Item<AdminTemplateState> t : view.currentTemplates()) {
      AdminTemplateState s = t.effective();
      boolean replaced =
          t.id().equals(replacing) || (s.bankCode().equals(bankCode) && s.messageType().equals(messageType));
      if (!replaced && t.survives() && s.active()) {
        Pattern p = TemplatePatternValidator.compileQuietly(s.pattern());
        if (p != null) {
          others.add(p);
        }
      }
    }
    int before = warnings.size();
    int bankSamples = 0;
    for (Sample sample : stored.samples()) {
      boolean mine = candidate.matcher(sample.sms()).find();
      boolean ownType = sample.bankCode().equals(bankCode) && Objects.equals(sample.messageType(), messageType);
      if (sample.bankCode().equals(bankCode)) {
        bankSamples++;
      }
      if (mine && others.stream().anyMatch(p -> p.matcher(sample.sms()).find())) {
        warnings.add(
            new Issue(
                "SAMPLE_MATCHES_TWO",
                ConfigEntityType.TEMPLATE,
                replacing,
                "A stored " + sample.bankCode() + " " + sample.messageType() + " message would match this template and another one."));
      } else if (ownType && !mine) {
        warnings.add(
            new Issue(
                "COVERAGE_LOSS",
                ConfigEntityType.TEMPLATE,
                replacing,
                "A stored " + bankCode + " " + messageType + " message is no longer read by this template."));
      }
    }
    for (String never : stored.mustNotMatch()) {
      if (candidate.matcher(never).find()) {
        warnings.add(
            new Issue(
                "READS_FORBIDDEN_MESSAGE",
                ConfigEntityType.TEMPLATE,
                replacing,
                "This template would read a message that must never be read (an OTP, marketing, declined or informational SMS)."));
        break;
      }
    }
    boolean passed = warnings.size() == before;
    return new Check(
        "REGRESSION",
        passed,
        passed
            ? bankSamples + " stored " + bankCode + " samples: each matches at most one template"
            : (warnings.size() - before) + " regression problem(s) against the stored samples");
  }

  /** Field → sub-map of the extracted values in a fixed order, for stable responses. */
  static Map<String, String> ordered(Map<String, String> in) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String k : List.of("amount", "currency", "direction", "merchant", "txnDate", "txnTime", "maskedTail")) {
      out.put(k, in.get(k));
    }
    return out;
  }
}
