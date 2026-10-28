package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.ConfigChangeOp;
import com.moneyreserve.domain.ConfigEntityType;
import com.moneyreserve.domain.MatchType;
import com.moneyreserve.dto.AdminCategoryState;
import com.moneyreserve.dto.AdminMerchantRuleInput;
import com.moneyreserve.dto.AdminMerchantRuleItem.Generated;
import com.moneyreserve.dto.AdminMerchantRuleItem;
import com.moneyreserve.dto.AdminMerchantRuleSpec;
import com.moneyreserve.dto.AdminMerchantRuleState;
import com.moneyreserve.dto.AdminMerchantRuleTest.CategoryRef;
import com.moneyreserve.dto.AdminMerchantRuleTest.DecidedBy;
import com.moneyreserve.dto.AdminMerchantRuleTest.Impact;
import com.moneyreserve.dto.AdminMerchantRuleTest.Suggestion;
import com.moneyreserve.dto.AdminMerchantRuleTest;
import com.moneyreserve.dto.AdminMerchantRuleTestRequest;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminUncategorisedMerchant;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.AdminMerchantRuleService;
import com.moneyreserve.service.calc.MerchantRuleBuilder.Level;
import com.moneyreserve.service.calc.MerchantRuleBuilder.NameProblem;
import com.moneyreserve.service.calc.MerchantRuleBuilder;
import com.moneyreserve.service.calc.MerchantRuleMatcher;
import com.moneyreserve.service.calc.MerchantText;
import com.moneyreserve.service.calc.MessageTypeCategories;
import com.moneyreserve.service.impl.AdminEffectiveView.Item;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant Rules for the admin (architecture doc §3.5, screens 05–10).
 *
 * <p>The Admin types names and picks EXACT / PREFIX / REGEX, a category and a priority level; {@link
 * MerchantRuleBuilder} turns that into the stored pattern, type and priority number. Every save is a
 * draft; the categoriser only sees a rule after publish. "Test this rule" runs the same {@link
 * MerchantRuleMatcher} and evaluation order as the categoriser over the effective rules with the
 * draft rule in place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMerchantRuleServiceImpl implements AdminMerchantRuleService {

  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 200;
  static final long IMPACT_TIMEOUT_MS = 2_000;
  /** Same threshold the publish check warns at; one value, kept in {@link AdminPublishValidator}. */
  static final double TOO_BROAD_SHARE = AdminPublishValidator.BROAD_RULE_SHARE;

  /** Stands in for a rule that is not saved yet when the test ranks it among the others. */
  private static final UUID DRAFT_RULE = new UUID(0, 0);

  /** The categoriser's final tie-break (rule id), with a draft losing to an existing rule. */
  private static final Comparator<Ranked> TIE_BREAK =
      Comparator.comparing(Ranked::isDraft).thenComparing(Ranked::id);

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminUsageCounts usage;
  private final BiTransactionRepository transactions;
  private final InsightsProperties properties;
  private final MerchantRuleMatcher matcher = new MerchantRuleMatcher();
  private final ExecutorService impactPool = Executors.newFixedThreadPool(2);

  @PreDestroy
  void shutdown() {
    impactPool.shutdownNow();
  }

  // --- M1 list -------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminPage<AdminMerchantRuleItem> list(
      String q, String matchType, UUID categoryId, String active, int page, int size) {
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    Map<UUID, Integer> order = evaluationOrder(rules);
    Map<String, Long> merchants = usage.transactionsByMerchant();
    List<AdminMerchantRuleItem> all =
        rules.stream()
            .map(r -> toItem(r, order, merchants, false))
            .filter(i -> matchType == null || matchType.isBlank() || matchType.equalsIgnoreCase(i.matchType()))
            .filter(i -> categoryId == null || categoryId.equals(i.categoryId()))
            .filter(i -> AdminListFilters.matchesActive(active, i.active()))
            .filter(i -> matchesText(q, i, rules))
            .sorted(
                Comparator.comparing(
                        (AdminMerchantRuleItem i) ->
                            i.evaluationOrder() == null ? Integer.MAX_VALUE : i.evaluationOrder())
                    .thenComparing(i -> String.join(",", i.names())))
            .toList();
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    int from = Math.min(page * s, all.size());
    int to = Math.min(from + s, all.size());
    return new AdminPage<>(
        all.subList(from, to), page, s, all.size(), (int) Math.ceil(all.size() / (double) s));
  }

  // --- M2 detail -----------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminMerchantRuleItem get(UUID id) {
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    Item<AdminMerchantRuleState> item = require(rules, id);
    return toItem(item, evaluationOrder(rules), usage.transactionsByMerchant(), true);
  }

  // --- M3 test -------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminMerchantRuleTest test(AdminMerchantRuleTestRequest request) {
    AdminMerchantRuleTestRequest.Rule form = request.rule();
    List<String> names = requireNames(form.matchType(), form.names());
    List<Item<AdminCategoryState>> categories = view.categories();
    requireActiveCategory(categories, form.categoryId());
    String needle = MerchantRuleMatcher.normalise(request.description());
    if (needle == null) {
      throw fieldInvalid("description", "must not be blank");
    }
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    Level level = Level.valueOf(form.priorityLevel());
    MerchantRuleBuilder.Generated gen = MerchantRuleBuilder.generate(form.matchType(), names);
    int priority = priorityFor(rules, form.ruleId(), form.categoryId(), level);
    AdminMerchantRuleSpec spec = new AdminMerchantRuleSpec(form.matchType(), names, level.name());
    AdminMerchantRuleState draft =
        new AdminMerchantRuleState(
            gen.matchType().name(), gen.pattern(), form.categoryId(), priority, true, spec);

    Map<UUID, Item<AdminCategoryState>> categoryById = AdminEffectiveView.byId(categories);
    Ranked winner = decide(rules, form.ruleId(), draft, needle, categoryById);
    boolean thisMatches = matcher.matches(gen.matchType(), gen.pattern(), needle);

    Suggestion suggestion = null;
    if (thisMatches && (winner == null || !winner.isDraft()) && level != Level.HIGH) {
      AdminMerchantRuleState high =
          new AdminMerchantRuleState(
              draft.matchType(), draft.pattern(), draft.categoryId(), MerchantRuleBuilder.HIGH_PRIORITY, true, spec);
      Ranked again = decide(rules, form.ruleId(), high, needle, categoryById);
      if (again != null && again.isDraft()) {
        suggestion = new Suggestion(Level.HIGH.name(), categoryById.get(form.categoryId()).effective().code());
      }
    }

    CategoryRef category;
    DecidedBy decidedBy = null;
    if (winner == null) {
      category = otherCategory(categories);
    } else {
      AdminCategoryState c = categoryById.get(winner.state().categoryId()).effective();
      category = new CategoryRef(c.code(), c.nameEn());
      MerchantRuleBuilder.Derived shown = shownAs(winner.state());
      decidedBy =
          new DecidedBy(
              winner.isDraft(),
              winner.isDraft() ? form.ruleId() : winner.id(),
              shown == null ? List.of() : shown.names(),
              shown == null ? winner.state().matchType() : shown.matchType(),
              MerchantRuleBuilder.level(winner.state().priority()).name(),
              matchedText(winner.state(), needle));
    }

    List<String> warnings = new ArrayList<>();
    Impact impact = null;
    String unavailable = null;
    try {
      impact =
          CompletableFuture.supplyAsync(() -> impact(gen, categories), impactPool)
              .get(IMPACT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException ex) {
      log.info("Merchant-rule impact count timed out after {} ms; shown as unavailable", IMPACT_TIMEOUT_MS);
      unavailable = "TIMEOUT";
    } catch (Exception ex) {
      log.error("Merchant-rule impact count failed; shown as unavailable", ex);
      unavailable = "ERROR";
    }
    if (impact != null) {
      long debits = transactions.countDebitsSince(since());
      if (debits > 0 && impact.wouldMatch() > TOO_BROAD_SHARE * debits) {
        warnings.add(
            "RULE_TOO_BROAD: this rule would match "
                + Math.round(impact.wouldMatch() * 100.0 / debits)
                + "% of last month's debit transactions.");
      }
    }
    if (form.names() != null && names.size() < form.names().stream().filter(Objects::nonNull).count()) {
      warnings.add("DUPLICATE_NAMES_REMOVED: repeated or blank names were ignored.");
    }

    // The tested description is customer-like text and is never logged.
    log.info(
        "bi-admin merchant rule tested matchType={} names={} priority={} thisRuleMatches={} category={} impactUnavailable={} warnings={}",
        gen.matchType(),
        names.size(),
        priority,
        thisMatches,
        category == null ? null : category.code(),
        unavailable,
        warnings.stream().map(w -> w.contains(":") ? w.substring(0, w.indexOf(':')) : w).toList());
    return new AdminMerchantRuleTest(
        category,
        decidedBy,
        thisMatches,
        suggestion,
        impact,
        unavailable,
        warnings,
        new Generated(
            gen.matchType().name(), gen.pattern(), priority, MerchantRuleBuilder.band(level), spec));
  }

  // --- M4–M6 writes --------------------------------------------------------------------------------

  @Override
  @Transactional
  public AdminWriteResult<AdminMerchantRuleItem> create(
      AdminMerchantRuleInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    requireActiveCategory(view.categories(), input.categoryId());
    AdminMerchantRuleState desired = toState(input, rules, null);
    requireUnique(rules, desired, null);
    UUID id = UUID.randomUUID();
    drafts.draft(ConfigEntityType.MERCHANT_RULE, id, ConfigChangeOp.CREATE, null, desired, userId);
    logRule("create", id, desired, userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminMerchantRuleItem> update(
      UUID id, AdminMerchantRuleInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    Item<AdminMerchantRuleState> item = require(rules, id);
    requireNotDeleting(item);
    requireActiveCategory(view.categories(), input.categoryId());
    AdminMerchantRuleState desired = keepSeedForm(item.published(), toState(input, rules, item));
    requireUnique(rules, desired, id);
    ConfigChangeOp op =
        item.published() == null ? ConfigChangeOp.CREATE : opFor(item.published(), desired);
    drafts.draft(ConfigEntityType.MERCHANT_RULE, id, op, item.published(), desired, userId);
    logRule(op.name(), id, desired, userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminMerchantRuleItem> delete(UUID id, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminMerchantRuleState> item = require(view.merchantRules(), id);
    requireNotDeleting(item);
    drafts.draft(ConfigEntityType.MERCHANT_RULE, id, ConfigChangeOp.DELETE, item.published(), null, userId);
    log.info("bi-admin merchant rule delete drafted id={} by={}", id, userId);
    return result(id);
  }

  private static void logRule(String op, UUID id, AdminMerchantRuleState s, UUID userId) {
    log.info(
        "bi-admin merchant rule {} drafted id={} matchType={} category={} priority={} active={} by={}",
        op,
        id,
        s.matchType(),
        s.categoryId(),
        s.priority(),
        s.active(),
        userId);
  }

  // --- building a rule -----------------------------------------------------------------------------

  private AdminMerchantRuleState toState(
      AdminMerchantRuleInput in, List<Item<AdminMerchantRuleState>> rules, Item<AdminMerchantRuleState> self) {
    UUID selfId = self == null ? null : self.id();
    if ("SIMPLE".equals(in.mode())) {
      if ("TERMS".equals(in.matchType())) {
        throw fieldInvalid("matchType", "use REGEX (contains) in SIMPLE mode; TERMS is the stored form");
      }
      List<String> names = requireNames(in.matchType(), in.names());
      if (in.priorityLevel() == null) {
        throw fieldInvalid("priorityLevel", "is required in SIMPLE mode");
      }
      Level level = Level.valueOf(in.priorityLevel());
      MerchantRuleBuilder.Generated gen = MerchantRuleBuilder.generate(in.matchType(), names);
      int priority = priorityFor(rules, selfId, in.categoryId(), level);
      return new AdminMerchantRuleState(
          gen.matchType().name(),
          gen.pattern(),
          in.categoryId(),
          priority,
          in.active(),
          new AdminMerchantRuleSpec(in.matchType(), names, level.name()));
    }
    if (in.pattern() == null || in.pattern().isBlank()) {
      throw fieldInvalid("pattern", "is required in ADVANCED mode");
    }
    if (in.priority() == null) {
      throw fieldInvalid("priority", "is required in ADVANCED mode");
    }
    String pattern =
        "REGEX".equals(in.matchType())
            ? in.pattern().trim()
            : "TERMS".equals(in.matchType())
                ? MerchantRuleBuilder.canonicalTerms(in.pattern())
                : MerchantRuleMatcher.normalise(in.pattern());
    List<String> problems = MerchantRuleBuilder.patternProblems(in.matchType(), pattern);
    if (!problems.isEmpty()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_PATTERN_INVALID,
          problems.get(0),
          problems.stream().map(p -> new AdminFieldError("pattern", p)).toList());
    }
    return new AdminMerchantRuleState(in.matchType(), pattern, in.categoryId(), in.priority(), in.active(), null);
  }

  /**
   * The priority number for a level. An edit that keeps a rule's level (and, for Normal, its
   * category) keeps its number, so re-saving a rule never reorders it.
   */
  private int priorityFor(
      List<Item<AdminMerchantRuleState>> rules, UUID selfId, UUID categoryId, Level level) {
    if (selfId != null) {
      Optional<Item<AdminMerchantRuleState>> self =
          rules.stream().filter(r -> r.id().equals(selfId)).findFirst();
      if (self.isPresent()) {
        // The published number first (going back to it cancels a draft), then the draft's.
        for (AdminMerchantRuleState current :
            java.util.Arrays.asList(self.get().published(), self.get().effective())) {
          if (current != null
              && MerchantRuleBuilder.level(current.priority()) == level
              && (level != Level.NORMAL || categoryId.equals(current.categoryId()))) {
            return current.priority();
          }
        }
      }
    }
    Integer block =
        rules.stream()
            .filter(r -> r.survives() && !r.id().equals(selfId))
            .map(Item::effective)
            .filter(s -> categoryId.equals(s.categoryId()) && MerchantRuleBuilder.level(s.priority()) == Level.NORMAL)
            .map(AdminMerchantRuleState::priority)
            .min(Integer::compare)
            .orElse(null);
    return MerchantRuleBuilder.priorityFor(level, block);
  }

  private static List<String> requireNames(String matchType, List<String> raw) {
    List<String> names = MerchantRuleBuilder.normalise(raw);
    List<NameProblem> problems = MerchantRuleBuilder.nameProblems(matchType, names);
    if (!problems.isEmpty()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_RULE_NAMES_INVALID,
          "Please check the merchant names: " + problems.get(0).message() + ".",
          problems.stream().map(p -> new AdminFieldError(p.field(), p.message())).toList());
    }
    return names;
  }

  private static void requireActiveCategory(List<Item<AdminCategoryState>> categories, UUID id) {
    boolean ok =
        categories.stream()
            .anyMatch(c -> c.id().equals(id) && c.survives() && c.effective().active());
    if (!ok) {
      throw fieldInvalid("categoryId", "must be an active category");
    }
  }

  private static void requireUnique(
      List<Item<AdminMerchantRuleState>> rules, AdminMerchantRuleState desired, UUID self) {
    boolean clash =
        rules.stream()
            .anyMatch(
                r ->
                    !r.id().equals(self)
                        && r.survives()
                        && r.effective().matchType().equals(desired.matchType())
                        && r.effective().pattern().equalsIgnoreCase(desired.pattern()));
    if (clash) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "Another rule already matches exactly these names this way.",
          AdminFieldError.of("names", "another rule already has the same match type and names"));
    }
  }

  private static void requireNotDeleting(Item<AdminMerchantRuleState> item) {
    if (!item.survives()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID, "This rule is waiting to be deleted. Discard that change first.");
    }
  }

  /**
   * A published rule without a saved form (every seed rule) that is re-saved from the simple form
   * with the same stored pattern and type keeps having no form: the form is derived from the
   * pattern on read, so storing it would only be a change with no effect.
   */
  private static AdminMerchantRuleState keepSeedForm(
      AdminMerchantRuleState published, AdminMerchantRuleState desired) {
    if (published != null
        && published.ruleSpec() == null
        && desired.ruleSpec() != null
        && published.matchType().equals(desired.matchType())
        && published.pattern().equals(desired.pattern())) {
      return new AdminMerchantRuleState(
          desired.matchType(), desired.pattern(), desired.categoryId(), desired.priority(), desired.active(), null);
    }
    return desired;
  }

  private static ConfigChangeOp opFor(AdminMerchantRuleState published, AdminMerchantRuleState desired) {
    if (published.active() != desired.active()
        && Objects.equals(published.withActive(desired.active()), desired)) {
      return desired.active() ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE;
    }
    return ConfigChangeOp.UPDATE;
  }

  // --- test helpers --------------------------------------------------------------------------------

  private record Ranked(UUID id, AdminMerchantRuleState state) {
    boolean isDraft() {
      return DRAFT_RULE.equals(id);
    }
  }

  /**
   * The rule the categoriser would pick for {@code needle}: the effective active rules, the one
   * being edited replaced by the draft, ordered as the categoriser orders them. A rule whose
   * category is inactive is skipped, as the categoriser skips it.
   */
  private Ranked decide(
      List<Item<AdminMerchantRuleState>> rules,
      UUID replaced,
      AdminMerchantRuleState draft,
      String needle,
      Map<UUID, Item<AdminCategoryState>> categories) {
    List<Ranked> candidates = new ArrayList<>();
    for (Item<AdminMerchantRuleState> r : rules) {
      if (r.survives() && r.effective().active() && !r.id().equals(replaced)) {
        candidates.add(new Ranked(r.id(), r.effective()));
      }
    }
    candidates.add(new Ranked(DRAFT_RULE, draft));
    // Ranked exactly as the categoriser ranks them (same final tie-break: rule id); among full
    // ties an existing rule beats the draft.
    return matcher
        .ranked(
            candidates,
            r -> r.state().priority(),
            r -> MatchType.valueOf(r.state().matchType()),
            r -> r.state().pattern(),
            needle,
            TIE_BREAK)
        .stream()
        .map(MerchantRuleMatcher.Match::rule)
        .filter(c -> activeCategory(categories, c.state().categoryId()))
        .findFirst()
        .orElse(null);
  }

  private static Comparator<Ranked> order() {
    Comparator<Ranked> byEngine =
        MerchantRuleMatcher.order(r -> r.state().priority(), r -> MatchType.valueOf(r.state().matchType()));
    // Among exact ties an existing rule is tried before the one being added.
    return byEngine.thenComparing(Ranked::isDraft);
  }

  private static boolean activeCategory(Map<UUID, Item<AdminCategoryState>> categories, UUID id) {
    Item<AdminCategoryState> c = categories.get(id);
    return c != null && c.survives() && c.effective().active();
  }

  private String matchedText(AdminMerchantRuleState rule, String needle) {
    return matcher.matchedText(MatchType.valueOf(rule.matchType()), rule.pattern(), needle).orElse(null);
  }

  private static CategoryRef otherCategory(List<Item<AdminCategoryState>> categories) {
    return categories.stream()
        .map(Item::effective)
        .filter(c -> AdminCategoryState.OTHER.equals(c.code()))
        .findFirst()
        .map(c -> new CategoryRef(c.code(), c.nameEn()))
        .orElse(new CategoryRef(AdminCategoryState.OTHER, "Other"));
  }

  /**
   * Debit transactions of the last 30 days this rule would match, counting only merchants seen for
   * at least k distinct customers (k ≥ 5), and how many of those are in OTHER today. Counts only.
   */
  private Impact impact(MerchantRuleBuilder.Generated gen, List<Item<AdminCategoryState>> categories) {
    int k = properties.getAdmin().effectiveMerchantMinCustomers();
    LocalDate from = since();
    Map<String, Long> customers = new HashMap<>();
    for (Object[] row : transactions.countCustomersByDebitMerchantSince(from)) {
      customers.put((String) row[0], ((Number) row[1]).longValue());
    }
    UUID other =
        categories.stream()
            .filter(c -> AdminCategoryState.OTHER.equals(c.effective().code()))
            .map(Item::id)
            .findFirst()
            .orElse(null);
    long would = 0;
    long inOther = 0;
    for (Object[] row : transactions.countDebitsByMerchantAndCategorySince(from)) {
      String merchant = (String) row[0];
      if (customers.getOrDefault(merchant, 0L) < k) {
        continue;
      }
      if (matcher.matches(gen.matchType(), gen.pattern(), MerchantRuleMatcher.normalise(merchant))) {
        long n = ((Number) row[2]).longValue();
        would += n;
        if (other != null && other.equals(row[1])) {
          inOther += n;
        }
      }
    }
    return new Impact("30d", would, inOther, k);
  }

  // --- L5 uncategorised report --------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public List<AdminUncategorisedMerchant> uncategorised(int limit) {
    int k = properties.getAdmin().effectiveMerchantMinCustomers();
    List<Item<AdminCategoryState>> categories = view.categories();
    UUID other =
        categories.stream()
            .filter(c -> AdminCategoryState.OTHER.equals(c.effective().code()))
            .map(Item::id)
            .findFirst()
            .orElse(null);
    if (other == null) {
      return List.of();
    }
    List<String> layer0 =
        MessageTypeCategories.types(properties.getCategorisation().getMessageTypeCategories());
    Map<String, Long> byMerchant = new HashMap<>();
    for (Object[] row : transactions.countUncategorisedDebitsByMerchantSince(since(), other, layer0)) {
      if (((Number) row[2]).longValue() < k) {
        continue;
      }
      String cleaned = MerchantText.clean((String) row[0]);
      if (cleaned != null) {
        byMerchant.merge(cleaned, ((Number) row[1]).longValue(), Long::sum);
      }
    }
    Map<UUID, Item<AdminCategoryState>> categoryById = AdminEffectiveView.byId(categories);
    List<Ranked> live = new ArrayList<>();
    for (Item<AdminMerchantRuleState> r : view.merchantRules()) {
      if (r.survives() && r.effective().active()) {
        live.add(new Ranked(r.id(), r.effective()));
      }
    }
    int max = Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
    List<AdminUncategorisedMerchant> out = new ArrayList<>();
    List<Map.Entry<String, Long>> sorted = new ArrayList<>(byMerchant.entrySet());
    sorted.sort(
        Comparator.comparing((Map.Entry<String, Long> e) -> e.getValue())
            .reversed()
            .thenComparing(Map.Entry::getKey));
    for (Map.Entry<String, Long> e : sorted) {
      if (out.size() >= max) {
        break;
      }
      Ranked hit =
          matcher
              .ranked(
                  live,
                  r -> r.state().priority(),
                  r -> MatchType.valueOf(r.state().matchType()),
                  r -> r.state().pattern(),
                  e.getKey(),
                  TIE_BREAK)
              .stream()
              .map(MerchantRuleMatcher.Match::rule)
              .filter(c -> activeCategory(categoryById, c.state().categoryId()))
              .findFirst()
              .orElse(null);
      if (hit != null && other.equals(hit.state().categoryId())) {
        // In OTHER on purpose (cash and bank-charge rule): nothing for the admin to do.
        continue;
      }
      String now =
          hit == null ? AdminCategoryState.OTHER : categoryById.get(hit.state().categoryId()).effective().code();
      out.add(new AdminUncategorisedMerchant(e.getKey(), e.getValue(), now));
    }
    // Counts only: merchant names are never logged.
    log.info("bi-admin uncategorised merchants listed count={} k={}", out.size(), k);
    return out;
  }

  // --- views ---------------------------------------------------------------------------------------

  private AdminWriteResult<AdminMerchantRuleItem> result(UUID id) {
    long revision = drafts.commit();
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    AdminMerchantRuleItem item =
        rules.stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .map(r -> toItem(r, evaluationOrder(rules), usage.transactionsByMerchant(), false))
            .orElse(null);
    return new AdminWriteResult<>(item, revision);
  }

  private AdminMerchantRuleItem toItem(
      Item<AdminMerchantRuleState> item,
      Map<UUID, Integer> order,
      Map<String, Long> merchants,
      boolean withGenerated) {
    AdminMerchantRuleState s = item.effective();
    MerchantRuleBuilder.Derived shown = shownAs(s);
    MatchType stored = MatchType.valueOf(s.matchType());
    long matches =
        merchants.entrySet().stream()
            .filter(e -> matcher.matches(stored, s.pattern(), e.getKey()))
            .mapToLong(Map.Entry::getValue)
            .sum();
    Level level = MerchantRuleBuilder.level(s.priority());
    return new AdminMerchantRuleItem(
        item.id(),
        item.state(),
        item.changeId(),
        shown == null ? List.of() : shown.names(),
        shown == null ? s.matchType() : shown.matchType(),
        s.categoryId(),
        level.name(),
        s.active(),
        shown == null,
        order.get(item.id()),
        new AdminMerchantRuleItem.Usage(matches),
        withGenerated
            ? new Generated(s.matchType(), s.pattern(), s.priority(), MerchantRuleBuilder.band(level), s.ruleSpec())
            : null);
  }

  /** The simple form of a rule: its saved form, else derived from a pattern the builder makes. */
  private static MerchantRuleBuilder.Derived shownAs(AdminMerchantRuleState s) {
    if (s.ruleSpec() != null && s.ruleSpec().names() != null && !s.ruleSpec().names().isEmpty()) {
      return new MerchantRuleBuilder.Derived(s.ruleSpec().matchType(), s.ruleSpec().names());
    }
    return MerchantRuleBuilder.derive(MatchType.valueOf(s.matchType()), s.pattern()).orElse(null);
  }

  /** 1-based position of each active, surviving rule in the order the categoriser tries them. */
  private static Map<UUID, Integer> evaluationOrder(List<Item<AdminMerchantRuleState>> rules) {
    List<Ranked> live =
        rules.stream()
            .filter(r -> r.survives() && r.effective().active())
            .map(r -> new Ranked(r.id(), r.effective()))
            .sorted(order())
            .toList();
    Map<UUID, Integer> out = new HashMap<>();
    for (int i = 0; i < live.size(); i++) {
      out.put(live.get(i).id(), i + 1);
    }
    return out;
  }

  private static boolean matchesText(
      String q, AdminMerchantRuleItem item, List<Item<AdminMerchantRuleState>> rules) {
    if (q == null || q.isBlank()) {
      return true;
    }
    String needle = q.trim().toUpperCase(Locale.ROOT);
    if (item.names().stream().anyMatch(n -> n.contains(needle))) {
      return true;
    }
    // Advanced-only rules have no names; let the Admin find them by the words in the pattern.
    return item.advancedOnly()
        && rules.stream()
            .filter(r -> r.id().equals(item.id()))
            .anyMatch(r -> r.effective().pattern().toUpperCase(Locale.ROOT).contains(needle));
  }

  private static Item<AdminMerchantRuleState> require(List<Item<AdminMerchantRuleState>> rules, UUID id) {
    return rules.stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(
            () -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That merchant rule does not exist."));
  }

  private static BudgetInsightsException fieldInvalid(String field, String message) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_FIELD_INVALID, "Some fields are not valid.", AdminFieldError.of(field, message));
  }

  private static LocalDate since() {
    return LocalDate.now().minusDays(30);
  }
}
