package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.BiBankParserTemplate;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.domain.MatchType;
import com.moipay.budgetinsights.dto.AdminBankState;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.dto.AdminValidation.Check;
import com.moipay.budgetinsights.dto.AdminValidation.Issue;
import com.moipay.budgetinsights.dto.AdminValidation;
import com.moipay.budgetinsights.repository.BiBankParserTemplateRepository;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.calc.MerchantRuleMatcher;
import com.moipay.budgetinsights.service.calc.MerchantTerms;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import com.moipay.budgetinsights.service.ingest.RedactedSampleSet.Sample;
import com.moipay.budgetinsights.service.ingest.RedactedSampleSet;
import com.moipay.budgetinsights.service.ingest.TemplateFieldMap;
import com.moipay.budgetinsights.service.ingest.TemplatePatternValidator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The pre-publish checks, run over the whole effective configuration (published values with every
 * pending change applied) — so a publish can only produce a configuration that passes them.
 *
 * <p>Errors block the publish. Warnings are allowed through once the Super Admin acknowledges them.
 */
@Component
@RequiredArgsConstructor
public class AdminPublishValidator {

  private final AdminEffectiveView view;
  private final RedactedSampleSet samples;
  private final BiBankParserTemplateRepository templateRows;
  private final BiBudgetCategoryLimitRepository limits;
  private final BiTransactionRepository transactions;
  private final InsightsProperties properties;
  private final MerchantRuleMatcher matcher = new MerchantRuleMatcher();

  /** One active template after publish, compiled once for the sample checks. */
  private record LiveTemplate(Item<AdminTemplateState> item, Pattern pattern) {
    String key() {
      return item.effective().bankCode() + " " + item.effective().messageType();
    }
  }

  public AdminValidation validate(long revision) {
    List<Issue> errors = new ArrayList<>();
    List<Issue> warnings = new ArrayList<>();
    List<Check> checks = new ArrayList<>();

    List<Item<AdminCategoryState>> categories =
        view.categories().stream().filter(Item::survives).toList();
    List<Item<AdminMerchantRuleState>> rules =
        view.merchantRules().stream()
            .filter(r -> r.survives() && r.effective().active())
            .toList();
    List<Item<AdminSenderState>> senders =
        view.senders().stream().filter(s -> s.survives() && s.effective().active()).toList();
    List<Item<AdminTemplateState>> templates =
        view.currentTemplates().stream()
            .filter(t -> t.survives() && t.effective().active())
            .toList();

    // --- categories --------------------------------------------------------------------------
    boolean otherOk =
        categories.stream()
            .anyMatch(c -> AdminCategoryState.OTHER.equals(c.effective().code()) && c.effective().active());
    if (!otherOk) {
      errors.add(
          new Issue(
              "OTHER_MISSING",
              ConfigEntityType.CATEGORY,
              null,
              "The OTHER category must exist and stay active: uncategorised transactions go there."));
    }
    checks.add(new Check("OTHER_ACTIVE", otherOk, "The OTHER category exists and is active"));

    // --- merchant rules ----------------------------------------------------------------------
    Map<UUID, Item<AdminCategoryState>> categoryById = AdminEffectiveView.byId(categories);
    int ruleErrors = errors.size();
    for (Item<AdminMerchantRuleState> r : rules) {
      Item<AdminCategoryState> target = categoryById.get(r.effective().categoryId());
      if (target == null || !target.effective().active()) {
        errors.add(
            new Issue(
                "RULE_CATEGORY_INACTIVE",
                ConfigEntityType.MERCHANT_RULE,
                r.id(),
                "An active merchant rule ("
                    + AdminChangeLabels.rule(r.effective())
                    + ") points at a category that is inactive or being deleted."));
      }
      if ("TERMS".equals(r.effective().matchType())) {
        List<String> keywords = MerchantTerms.split(r.effective().pattern());
        Optional<String> problem =
            MerchantTerms.listProblem(keywords)
                .or(() -> keywords.stream().map(MerchantTerms::problem).flatMap(Optional::stream).findFirst());
        problem.ifPresent(
            p ->
                errors.add(
                    new Issue(
                        "MOI.BI.ADM.E.013",
                        ConfigEntityType.MERCHANT_RULE,
                        r.id(),
                        "Merchant rule keywords are not valid: " + p)));
      }
      if ("REGEX".equals(r.effective().matchType())) {
        try {
          Pattern.compile(r.effective().pattern());
        } catch (PatternSyntaxException ex) {
          errors.add(
              new Issue(
                  "MOI.BI.ADM.E.013",
                  ConfigEntityType.MERCHANT_RULE,
                  r.id(),
                  "Merchant rule pattern does not compile: " + r.effective().pattern()));
        }
      }
    }
    checks.add(
        new Check(
            "RULES_VALID",
            errors.size() == ruleErrors,
            "Every active merchant rule compiles and points at an active category"));

    // --- templates ---------------------------------------------------------------------------
    int templateErrors = errors.size();
    List<LiveTemplate> live = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    for (Item<AdminTemplateState> t : templates) {
      AdminTemplateState s = t.effective();
      String key = s.bankCode() + " " + s.messageType();
      if (!keys.add(key)) {
        errors.add(
            new Issue(
                "TEMPLATE_DUPLICATE",
                ConfigEntityType.TEMPLATE,
                t.id(),
                "Two active templates for " + key + "."));
      }
      List<String> portability = TemplatePatternValidator.problems(s.pattern());
      for (String p : portability) {
        errors.add(new Issue("MOI.BI.ADM.E.013", ConfigEntityType.TEMPLATE, t.id(), key + ": " + p));
      }
      TemplateFieldMap.Check map =
          TemplateFieldMap.check(s.fieldMap(), TemplatePatternValidator.groupNames(s.pattern()));
      for (String e : map.errors()) {
        errors.add(new Issue("MOI.BI.ADM.E.014", ConfigEntityType.TEMPLATE, t.id(), key + ": " + e));
      }
      if (t.change() != null) {
        for (String w : map.warnings()) {
          warnings.add(new Issue("FIELD_MAP_WARNING", ConfigEntityType.TEMPLATE, t.id(), key + ": " + w));
        }
      }
      if (portability.isEmpty()) {
        live.add(new LiveTemplate(t, Pattern.compile(s.pattern())));
      }
    }
    // An active row the effective view does not show (an older version of a bank + type left
    // active) stays live after publish, because publish only touches items with a change.
    Set<UUID> shown = new HashSet<>();
    view.currentTemplates().forEach(t -> shown.add(t.id()));
    for (BiBankParserTemplate row : templateRows.findAllByIsActiveTrue()) {
      if (!shown.contains(row.getId())) {
        errors.add(
            new Issue(
                "TEMPLATE_DUPLICATE",
                ConfigEntityType.TEMPLATE,
                row.getId(),
                "Two active templates for "
                    + row.getBankCode()
                    + " "
                    + row.getMessageType()
                    + " (version "
                    + row.getTemplateVersion()
                    + " is still active)."));
      }
    }
    checks.add(
        new Check(
            "TEMPLATES_VALID",
            errors.size() == templateErrors,
            "Every active template is valid on Java, Android and iOS and its field map is complete"));

    // --- senders <-> templates ---------------------------------------------------------------
    int senderErrors = errors.size();
    for (LiveTemplate lt : live) {
      AdminTemplateState s = lt.item().effective();
      for (JsonNode sender : s.fieldMap().path("senders")) {
        boolean ok =
            senders.stream()
                .anyMatch(
                    x ->
                        x.effective().bankCode().equals(s.bankCode())
                            && x.effective().senderId().equals(sender.asText()));
        if (!ok) {
          errors.add(
              new Issue(
                  "TEMPLATE_SENDER_INACTIVE",
                  ConfigEntityType.TEMPLATE,
                  lt.item().id(),
                  lt.key()
                      + " reads messages from "
                      + sender.asText()
                      + ", which is not an active sender of that bank."));
        }
      }
    }
    for (Item<AdminSenderState> x : senders) {
      boolean used =
          live.stream()
              .anyMatch(
                  lt ->
                      lt.item().effective().bankCode().equals(x.effective().bankCode())
                          && AdminSenderServiceImpl.lists(lt.item().effective(), x.effective().senderId()));
      if (!used) {
        warnings.add(
            new Issue(
                "SENDER_WITHOUT_TEMPLATE",
                ConfigEntityType.SENDER,
                x.id(),
                "Sender "
                    + x.effective().senderId()
                    + " has no active template yet. Handsets will read its SMS but create no"
                    + " transactions."));
      }
    }
    checks.add(
        new Check(
            "SENDERS_CONSISTENT",
            errors.size() == senderErrors,
            "Every template's senders are active senders of its bank"));

    // --- regression against the redacted sample set ------------------------------------------
    int sampleErrors = errors.size();
    List<Pattern> publishedPatterns =
        templateRows.findAllByIsActiveTrue().stream()
            .map(BiBankParserTemplate::getPattern)
            .map(TemplatePatternValidator::compileQuietly)
            .filter(p -> p != null)
            .toList();
    int stored = 0;
    for (Sample sample : samples.samples()) {
      stored++;
      List<LiveTemplate> hits =
          live.stream().filter(lt -> lt.pattern().matcher(sample.sms()).find()).toList();
      if (hits.size() > 1) {
        errors.add(
            new Issue(
                "SAMPLE_MATCHES_TWO",
                ConfigEntityType.TEMPLATE,
                hits.get(1).item().id(),
                "A stored "
                    + sample.bankCode()
                    + " message now matches two templates: "
                    + hits.stream().map(LiveTemplate::key).toList()
                    + "."));
      } else if (hits.isEmpty()
          && publishedPatterns.stream().anyMatch(p -> p.matcher(sample.sms()).find())) {
        warnings.add(
            new Issue(
                "COVERAGE_LOSS",
                ConfigEntityType.TEMPLATE,
                null,
                "A stored "
                    + sample.bankCode()
                    + " "
                    + sample.messageType()
                    + " message that is read today would no longer be read by any template."));
      }
    }
    for (String negative : samples.mustNotMatch()) {
      for (LiveTemplate lt : live) {
        if (lt.pattern().matcher(negative).find()) {
          errors.add(
              new Issue(
                  "READS_FORBIDDEN_MESSAGE",
                  ConfigEntityType.TEMPLATE,
                  lt.item().id(),
                  lt.key()
                      + " would read a message that must never be read (an OTP, marketing,"
                      + " declined or informational SMS)."));
          break;
        }
      }
    }
    checks.add(
        new Check(
            "REGRESSION",
            errors.size() == sampleErrors,
            stored
                + " stored bank messages each match at most one template, and no OTP or marketing"
                + " message matches any"));

    // --- banks ---------------------------------------------------------------------------------
    int bankErrors = errors.size();
    Map<String, Item<AdminBankState>> bankByCode = new java.util.HashMap<>();
    for (Item<AdminBankState> b : view.banks()) {
      AdminBankState base = b.published() != null ? b.published() : b.effective();
      bankByCode.put(base.bankCode(), b);
    }
    Set<String> bankChecked = new HashSet<>();
    for (Item<AdminSenderState> x : senders) {
      bankStillOn(x.effective().bankCode(), bankByCode, bankChecked, errors);
    }
    for (Item<AdminTemplateState> t : templates) {
      bankStillOn(t.effective().bankCode(), bankByCode, bankChecked, errors);
    }
    checks.add(
        new Check(
            "BANKS_ACTIVE",
            errors.size() == bankErrors,
            "Every active sender and template belongs to an active bank"));

    // --- budget policy -----------------------------------------------------------------------
    int policyErrors = errors.size();
    for (Item<AdminBudgetPolicyState> p : view.budgetPolicy()) {
      if (p.change() == null || p.effective() == null) {
        continue;
      }
      for (String[] problem : p.effective().problems()) {
        errors.add(
            new Issue(
                "MOI.BI.ADM.E.006",
                ConfigEntityType.BUDGET_POLICY,
                p.id(),
                "Budget policy: " + AdminBudgetPolicyState.describe(problem) + "."));
      }
      if (p.effective().allAlertsOff()) {
        warnings.add(
            new Issue(
                "ALERTS_ALL_OFF",
                ConfigEntityType.BUDGET_POLICY,
                p.id(),
                "Every budget alert type is turned off. Customers will get no new budget alerts."));
      }
    }
    checks.add(
        new Check(
            "BUDGET_POLICY_VALID",
            errors.size() == policyErrors,
            "The budget policy's 50/30/20 split adds up to 100 and every value is in range"));

    // --- merchant rules that would catch too much ----------------------------------------------
    broadRuleWarnings(rules, warnings);

    // --- category deactivations with customer budgets -----------------------------------------
    for (Item<AdminCategoryState> c : view.categories()) {
      if (c.change() != null
          && c.change().getOp() == ConfigChangeOp.DEACTIVATE
          && limits.countByCategoryId(c.id()) > 0) {
        warnings.add(
            new Issue(
                "CATEGORY_HAS_BUDGETS",
                ConfigEntityType.CATEGORY,
                c.id(),
                "Customers have budget limits on "
                    + c.effective().code()
                    + ". They keep them, but new transactions will not land there."));
      }
    }

    return new AdminValidation(revision, errors, warnings, checks);
  }

  private static void bankStillOn(
      String code,
      Map<String, Item<AdminBankState>> bankByCode,
      Set<String> checked,
      List<Issue> errors) {
    if (code == null || !checked.add(code)) {
      return;
    }
    Item<AdminBankState> bank = bankByCode.get(code);
    if (bank == null || bank.change() == null) {
      // Only a pending bank edit is judged here; sender and template writes already check the
      // bank, and an older inconsistency must not block every publish.
      return;
    }
    if (!bank.survives() || !bank.effective().active()) {
      errors.add(
          new Issue(
              "BANK_INACTIVE",
              ConfigEntityType.BANK,
              bank.id(),
              "Bank "
                  + code
                  + " is being turned off or deleted, but active SMS senders or templates still read"
                  + " its messages."));
    }
  }

  /** Share of last month's transactions above which a changed rule gets a warning (doc §5.2). */
  static final double BROAD_RULE_SHARE = 0.30;

  /**
   * Warns about a changed, active merchant rule that would match more than 30 % of the last 30 days'
   * debit transactions. Counts only; no merchant name leaves this method.
   */
  private void broadRuleWarnings(
      List<Item<AdminMerchantRuleState>> rules, List<Issue> warnings) {
    List<Item<AdminMerchantRuleState>> changed =
        rules.stream().filter(r -> r.change() != null).toList();
    if (changed.isEmpty()) {
      return;
    }
    Map<String, Long> byMerchant = new java.util.HashMap<>();
    for (Object[] row :
        transactions.countDebitsByMerchantAndCategorySince(
            LocalDate.now(properties.zoneId()).minusDays(30))) {
      String merchant = MerchantRuleMatcher.normalise((String) row[0]);
      if (merchant != null) {
        byMerchant.merge(merchant, ((Number) row[2]).longValue(), Long::sum);
      }
    }
    long total = byMerchant.values().stream().mapToLong(Long::longValue).sum();
    if (total == 0) {
      return;
    }
    for (Item<AdminMerchantRuleState> r : changed) {
      AdminMerchantRuleState rule = r.effective();
      MatchType type;
      try {
        type = MatchType.valueOf(rule.matchType());
      } catch (RuntimeException ex) {
        continue;
      }
      long hit =
          byMerchant.entrySet().stream()
              .filter(e -> matcher.matches(type, rule.pattern(), e.getKey()))
              .mapToLong(Map.Entry::getValue)
              .sum();
      double share = (double) hit / total;
      if (share > BROAD_RULE_SHARE) {
        warnings.add(
            new Issue(
                "RULE_TOO_BROAD",
                ConfigEntityType.MERCHANT_RULE,
                r.id(),
                "Merchant rule "
                    + AdminChangeLabels.rule(rule)
                    + " would match "
                    + Math.round(share * 100)
                    + "% of last month's transactions."));
      }
    }
  }
}
