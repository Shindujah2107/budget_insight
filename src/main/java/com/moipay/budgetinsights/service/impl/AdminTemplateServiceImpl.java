package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.dto.AdminConfigList;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminTemplateAnalyseRequest;
import com.moipay.budgetinsights.dto.AdminTemplateAnalysis;
import com.moipay.budgetinsights.dto.AdminTemplateAnalysis.ExistingMatch;
import com.moipay.budgetinsights.dto.AdminTemplateCheck;
import com.moipay.budgetinsights.dto.AdminTemplateGenerateRequest;
import com.moipay.budgetinsights.dto.AdminTemplateGenerated;
import com.moipay.budgetinsights.dto.AdminTemplateInput;
import com.moipay.budgetinsights.dto.AdminTemplateItem;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.dto.AdminTemplateValidateRequest;
import com.moipay.budgetinsights.dto.AdminValidation;
import com.moipay.budgetinsights.dto.AdminValidation.Issue;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.service.AdminConfigService;
import com.moipay.budgetinsights.service.AdminMessageTypeService;
import com.moipay.budgetinsights.service.AdminTemplateService;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import com.moipay.budgetinsights.service.impl.AdminUsageCounts.Counts;
import com.moipay.budgetinsights.service.ingest.TemplatePatternGenerator;
import com.moipay.budgetinsights.service.ingest.TemplatePatternGenerator.SpecException;
import com.moipay.budgetinsights.service.ingest.TemplatePatternValidator;
import com.moipay.budgetinsights.service.ingest.TemplateSampleAnalyser;
import com.moipay.budgetinsights.service.ingest.TemplateSampleAnalyser.AnnotationException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SMS Parser Templates for the admin (architecture doc §3.7, screens 14–22).
 *
 * <p>A template is drafted either from the wizard's builder spec — the server regenerates pattern
 * and field map from it, so what was previewed is what is stored — or, in Advanced mode, from a raw
 * pattern and field map that must pass the portable-pattern and field-map checks. Templates are
 * never edited in place: publishing an edit inserts version N+1 and retires N; retiring only turns
 * a version off.
 *
 * <p>Samples reach {@link #analyse}, {@link #generate} and {@link #validate} only. They are held in
 * local variables and never stored, cached, logged or returned.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminTemplateServiceImpl implements AdminTemplateService {

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminUsageCounts usage;
  private final AdminReleaseVersion releases;
  private final AdminConfigService config;
  private final AdminTemplateChecks checks;
  private final AdminMessageTypeService messageTypes;
  private final AdminTemplateActivity activity;

  // --- T1 / T2 reads -------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminConfigList<AdminTemplateItem> list(String q, String active, String bank) {
    List<Item<AdminTemplateState>> all = view.templates();
    Map<UUID, Counts> counts = usage.transactionsByTemplate();
    List<AdminTemplateItem> items =
        view.currentTemplates().stream()
            .filter(t -> bank == null || bank.isBlank() || bank.equals(t.effective().bankCode()))
            .map(t -> toItem(t, all, counts, false))
            .filter(i -> AdminListFilters.matchesActive(active, i.active()))
            .filter(
                i ->
                    AdminListFilters.matchesText(
                        q, i.bankCode(), i.bankName(), i.messageType(), i.logicalType(), i.templateView()))
            .sorted(Comparator.comparing(AdminTemplateItem::bankCode).thenComparing(AdminTemplateItem::messageType))
            .toList();
    return new AdminConfigList<>(items, drafts.revision(), releases.current());
  }

  @Override
  @Transactional(readOnly = true)
  public AdminTemplateItem get(UUID id) {
    List<Item<AdminTemplateState>> all = view.templates();
    return toItem(require(all, id), all, usage.transactionsByTemplate(), true);
  }

  // --- T3 / T4 / T5 tools --------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminTemplateAnalysis analyse(AdminTemplateAnalyseRequest request) {
    config.requireBank(request.bankCode());
    AdminTemplateChecks.requireAcceptableSamples(List.of(request.sample()));
    TemplateSampleAnalyser.Analysis a = TemplateSampleAnalyser.analyse(request.sample());
    ExistingMatch existing = existingMatch(request.bankCode(), List.of(request.sample()), request.templateId());
    log.info(
        "bi-admin template sample analysed bank={} tokens={} suggestions={} piiWarnings={} matchesExisting={}",
        request.bankCode(),
        a.tokens().size(),
        a.suggestions().size(),
        a.piiWarnings().size(),
        existing == null ? "none" : existing.messageType());
    return new AdminTemplateAnalysis(a.length(), a.tokens(), a.suggestions(), a.piiWarnings(), existing);
  }

  /**
   * The first active template (live or draft, not being retired) that already reads one of the
   * samples: the chosen bank's templates first, then any other bank's. The template being edited
   * ({@code excludeId}) may read its own sample.
   */
  private ExistingMatch existingMatch(String bankCode, List<String> samples, UUID excludeId) {
    ExistingMatch other = null;
    for (Item<AdminTemplateState> t : view.currentTemplates()) {
      AdminTemplateState s = t.effective();
      if (s == null || !t.survives() || !s.active() || t.id().equals(excludeId)) {
        continue;
      }
      boolean sameBank = s.bankCode().equals(bankCode);
      if (!sameBank && other != null) {
        continue;
      }
      Pattern p = TemplatePatternValidator.compileQuietly(s.pattern());
      if (p == null || samples.stream().noneMatch(x -> x != null && p.matcher(x).find())) {
        continue;
      }
      ExistingMatch match =
          new ExistingMatch(
              t.id(),
              s.messageType(),
              s.bankCode(),
              config.bankName(s.bankCode()),
              t.published() == null ? null : t.published().templateVersion(),
              t.change() != null,
              sameBank);
      if (sameBank) {
        return match;
      }
      other = match;
    }
    return other;
  }

  @Override
  @Transactional(readOnly = true)
  public AdminTemplateGenerated generate(AdminTemplateGenerateRequest request) {
    config.requireBank(request.bankCode());
    if (!Boolean.TRUE.equals(request.redactionConfirmed())) {
      throw AdminTemplateChecks.sampleRejected(
          "redactionConfirmed", "confirm the sample is redacted before it is used");
    }
    List<String> samples = new ArrayList<>();
    samples.add(request.sample());
    if (request.extraSamples() != null) {
      samples.addAll(request.extraSamples());
    }
    AdminTemplateChecks.requireAcceptableSamples(samples);
    ExistingMatch duplicate = existingMatch(request.bankCode(), samples, request.templateId());
    if (duplicate != null && duplicate.sameBank()) {
      log.info(
          "bi-admin template generate refused: sample already read by bank={} type={}",
          duplicate.bankCode(),
          duplicate.messageType());
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "This SMS is already read by the "
              + duplicate.messageType()
              + " template of this bank. The same format cannot be added twice; edit that template instead.",
          AdminFieldError.of("sample", "already read by " + duplicate.messageType()));
    }
    // A new template takes its group from the message type list; an edit keeps the template's own.
    String logicalType = request.logicalType();
    if (request.templateId() == null) {
      messageTypes.requireUsableForNewTemplate(request.messageType());
      logicalType = messageTypes.logicalTypeFor(request.messageType()).orElse(logicalType);
    }
    List<String> senders =
        request.senders() == null || request.senders().isEmpty()
            ? activeSenders(request.bankCode())
            : request.senders();

    JsonNode spec;
    try {
      spec =
          TemplateSampleAnalyser.buildSpec(
              request.sample(),
              request.annotations().stream()
                  .map(
                      a ->
                          new TemplateSampleAnalyser.Annotation(
                              a.start(), a.end(), a.field(), a.direction(), a.counterpartWords(), a.format(), a.ignoreKind()))
                  .toList(),
              request.fixed(),
              request.options(),
              logicalType,
              request.instrumentType(),
              senders);
    } catch (AnnotationException ex) {
      throw incomplete(ex.problems());
    }
    TemplatePatternGenerator.Result gen = generateFrom(spec);
    checks.requireValid(gen.pattern(), gen.fieldMap());
    AdminTemplateCheck check =
        checks.run(request.bankCode(), request.messageType(), gen.pattern(), gen.fieldMap(), samples, request.templateId());
    if (check.extracted().isEmpty() || !check.extracted().get(0).matched()) {
      log.info(
          "bi-admin template generate: pattern does not read the sample bank={} type={}",
          request.bankCode(),
          request.messageType());
      throw incomplete(List.of("The generated template does not read the sample. Check the highlights and date/time formats."));
    }
    List<Issue> senderWarnings = senderWarnings(request.bankCode(), senders, request.templateId());
    AdminTemplateCheck merged =
        senderWarnings.isEmpty()
            ? check
            : new AdminTemplateCheck(check.checks(), concat(check.warnings(), senderWarnings), check.extracted());
    logCheck("generated", request.bankCode(), request.messageType(), samples.size(), merged);
    return new AdminTemplateGenerated(spec, gen.templateView(), gen.pattern(), gen.fieldMap(), merged);
  }

  @Override
  @Transactional(readOnly = true)
  public AdminTemplateCheck validate(AdminTemplateValidateRequest request) {
    config.requireBank(request.bankCode());
    AdminTemplateChecks.requireAcceptableSamples(request.samples());
    checks.requireValid(request.pattern(), request.fieldMap());
    String type = request.messageType();
    if (type == null && request.templateId() != null) {
      type =
          view.templates().stream()
              .filter(t -> t.id().equals(request.templateId()))
              .map(t -> t.effective().messageType())
              .findFirst()
              .orElse(null);
    }
    AdminTemplateCheck result =
        checks.run(request.bankCode(), type, request.pattern(), request.fieldMap(), request.samples(), request.templateId());
    logCheck(
        "validated",
        request.bankCode(),
        type,
        request.samples() == null ? 0 : request.samples().size(),
        result);
    return result;
  }

  /** Check keys, warning codes and match counts only: never the sample or extracted values. */
  private static void logCheck(
      String what, String bank, String type, int samples, AdminTemplateCheck check) {
    log.info(
        "bi-admin template {} bank={} type={} samples={} matched={} failedChecks={} warnings={}",
        what,
        bank,
        type,
        samples,
        nz(check.extracted()).stream().filter(AdminTemplateCheck.Extracted::matched).count(),
        nz(check.checks()).stream().filter(c -> !c.passed()).map(AdminValidation.Check::key).toList(),
        nz(check.warnings()).stream().map(AdminValidation.Issue::code).toList());
  }

  private static <T> List<T> nz(List<T> list) {
    return list == null ? List.of() : list;
  }

  // --- T6 / T7 / T8 writes -------------------------------------------------------------------------

  @Override
  @Transactional
  public AdminWriteResult<AdminTemplateItem> create(AdminTemplateInput request, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    config.requireBank(request.bankCode());
    messageTypes.requireUsableForNewTemplate(request.messageType());
    AdminTemplateInput input = withListedLogicalType(request);
    List<Item<AdminTemplateState>> all = view.templates();
    boolean exists =
        all.stream()
            .anyMatch(
                t ->
                    t.survives()
                        && key(t).equals(input.bankCode() + " " + input.messageType()));
    if (exists) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "A template for " + input.bankCode() + " " + input.messageType() + " already exists. Edit it instead.",
          AdminFieldError.of("messageType", "already has a template for this bank (active, retired or draft)"));
    }
    AdminTemplateState desired = materialise(input, 0);
    UUID id = UUID.randomUUID();
    List<Issue> warnings = saveWarnings(desired, null);
    drafts.draft(ConfigEntityType.TEMPLATE, id, ConfigChangeOp.CREATE, null, desired, userId);
    log.info(
        "bi-admin template create drafted id={} bank={} type={} mode={} active={} warnings={} by={}",
        id,
        input.bankCode(),
        input.messageType(),
        input.mode(),
        desired.active(),
        warnings.size(),
        userId);
    return result(id, warnings);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminTemplateItem> update(
      UUID id, AdminTemplateInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminTemplateState> item = require(view.templates(), id);
    AdminTemplateState base = item.published() != null ? item.published() : item.effective();
    if (!base.bankCode().equals(input.bankCode())) {
      throw fieldInvalid("bankCode", "cannot change; create a template for the other bank instead");
    }
    if (!base.messageType().equals(input.messageType())) {
      throw fieldInvalid("messageType", "cannot change; create a template for the other message type instead");
    }
    AdminTemplateState desired =
        materialise(input, item.published() == null ? 0 : item.published().templateVersion());
    List<Issue> warnings = desired.active() ? saveWarnings(desired, id) : List.of();
    ConfigChangeOp op;
    if (item.published() == null) {
      op = ConfigChangeOp.CREATE;
    } else if (item.published().active()
        && !desired.active()
        && Objects.equals(item.published().withActive(false), desired)) {
      op = ConfigChangeOp.RETIRE;
    } else {
      op = ConfigChangeOp.UPDATE;
    }
    drafts.draft(ConfigEntityType.TEMPLATE, id, op, item.published(), desired, userId);
    log.info(
        "bi-admin template {} drafted id={} bank={} type={} mode={} warnings={} by={}",
        op,
        id,
        input.bankCode(),
        input.messageType(),
        input.mode(),
        warnings.size(),
        userId);
    return result(id, warnings);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminTemplateItem> retire(UUID id, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminTemplateState> item = require(view.templates(), id);
    if (item.published() != null && !item.published().active() && item.change() == null) {
      throw fieldInvalid("id", "this template version is already retired");
    }
    drafts.draft(
        ConfigEntityType.TEMPLATE,
        id,
        ConfigChangeOp.RETIRE,
        item.published(),
        item.effective().withActive(false),
        userId);
    log.info(
        "bi-admin template retire drafted id={} bank={} type={} by={}",
        id,
        item.effective().bankCode(),
        item.effective().messageType(),
        userId);
    return result(id, List.of());
  }

  // --- helpers -------------------------------------------------------------------------------------

  /**
   * A new template's group is the one its message type has on the list: written into the builder
   * spec (BUILDER, the field map is generated from it) or the field map (ADVANCED). Unchanged when
   * the type is not on the list or the input carries no spec / map to write it into.
   */
  private AdminTemplateInput withListedLogicalType(AdminTemplateInput in) {
    Optional<String> listed = messageTypes.logicalTypeFor(in.messageType());
    if (listed.isEmpty()) {
      return in;
    }
    String group = listed.get();
    if ("BUILDER".equals(in.mode()) && in.builderSpec() instanceof ObjectNode spec
        && !group.equals(spec.path("logicalType").asText(null))) {
      ObjectNode copy = spec.deepCopy();
      copy.put("logicalType", group);
      return new AdminTemplateInput(
          in.mode(), in.bankCode(), in.messageType(), in.pattern(), in.fieldMap(), copy, in.active());
    }
    if (!"BUILDER".equals(in.mode()) && in.fieldMap() instanceof ObjectNode map
        && !group.equals(map.path("logicalType").asText(null))) {
      ObjectNode copy = map.deepCopy();
      copy.put("logicalType", group);
      return new AdminTemplateInput(
          in.mode(), in.bankCode(), in.messageType(), in.pattern(), copy, in.builderSpec(), in.active());
    }
    return in;
  }

  /** The state to store for a save: regenerated from the spec (BUILDER) or checked as given. */
  private AdminTemplateState materialise(AdminTemplateInput in, int publishedVersion) {
    if ("BUILDER".equals(in.mode())) {
      if (in.builderSpec() == null || !in.builderSpec().isObject()) {
        throw fieldInvalid("builderSpec", "is required in BUILDER mode");
      }
      // Anything the client sent as pattern / fieldMap is ignored: the spec is the source.
      TemplatePatternGenerator.Result gen = generateFrom(in.builderSpec());
      checks.requireValid(gen.pattern(), gen.fieldMap());
      return new AdminTemplateState(
          in.bankCode(), in.messageType(), gen.pattern(), gen.fieldMap(), publishedVersion, in.active(), in.builderSpec());
    }
    if (in.pattern() == null || in.pattern().isBlank()) {
      throw fieldInvalid("pattern", "is required in ADVANCED mode");
    }
    if (in.fieldMap() == null || !in.fieldMap().isObject()) {
      throw fieldInvalid("fieldMap", "is required in ADVANCED mode");
    }
    checks.requireValid(in.pattern(), in.fieldMap());
    return new AdminTemplateState(
        in.bankCode(), in.messageType(), in.pattern(), in.fieldMap(), publishedVersion, in.active(), null);
  }

  private static TemplatePatternGenerator.Result generateFrom(JsonNode spec) {
    try {
      return TemplatePatternGenerator.generate(spec);
    } catch (SpecException ex) {
      if (ex.literalPii()) {
        throw BudgetInsightsException.withDetails(
            ErrorCode.ADMIN_SPEC_LITERAL_PII,
            "The template would store " + ex.problems().get(0).replaceFirst("^segment [0-9]+: ", ""),
            ex.problems().stream().map(p -> new AdminFieldError("builderSpec", p)).toList());
      }
      throw incomplete(ex.problems());
    }
  }

  private static BudgetInsightsException incomplete(List<String> problems) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_SPEC_INCOMPLETE,
        "The template is not complete yet: " + problems.get(0) + ".",
        problems.stream().map(p -> new AdminFieldError("annotations", p)).toList());
  }

  /** Regression and sender warnings for a draft; they return as publish warnings or errors. */
  private List<Issue> saveWarnings(AdminTemplateState s, UUID replacing) {
    AdminTemplateCheck check = checks.run(s.bankCode(), s.messageType(), s.pattern(), s.fieldMap(), List.of(), replacing);
    List<String> senders = new ArrayList<>();
    s.fieldMap().path("senders").forEach(x -> senders.add(x.asText()));
    return concat(check.warnings(), senderWarnings(s.bankCode(), senders, replacing));
  }

  private List<Issue> senderWarnings(String bank, List<String> senders, UUID target) {
    List<String> active = activeSenders(bank);
    List<Issue> out = new ArrayList<>();
    for (String s : senders) {
      if (!active.contains(s)) {
        out.add(
            new Issue(
                "TEMPLATE_SENDER_INACTIVE",
                ConfigEntityType.TEMPLATE,
                target,
                "Sender " + s + " is not an active sender of bank " + bank + ". Add it on the Senders tab, or publishing will be blocked."));
      }
    }
    return out;
  }

  private List<String> activeSenders(String bank) {
    return view.senders().stream()
        .filter(x -> x.survives() && x.effective().active() && x.effective().bankCode().equals(bank))
        .map(Item::effective)
        .map(AdminSenderState::senderId)
        .toList();
  }

  private AdminWriteResult<AdminTemplateItem> result(UUID id, List<Issue> warnings) {
    long revision = drafts.commit();
    List<Item<AdminTemplateState>> all = view.templates();
    AdminTemplateItem item =
        all.stream()
            .filter(t -> t.id().equals(id))
            .findFirst()
            .map(t -> toItem(t, all, usage.transactionsByTemplate(), false))
            .orElse(null);
    return new AdminWriteResult<>(item, revision, warnings);
  }

  private AdminTemplateItem toItem(
      Item<AdminTemplateState> t, List<Item<AdminTemplateState>> all, Map<UUID, Counts> counts, boolean detail) {
    AdminTemplateState s = t.effective();
    String k = key(t);
    List<Item<AdminTemplateState>> versions =
        all.stream().filter(v -> v.published() != null && key(v).equals(k)).toList();
    long recent = 0;
    long total = 0;
    OffsetDateTime lastRead = null;
    Map<UUID, OffsetDateTime> lastByTemplate = usage.lastTransactionByTemplate();
    for (Item<AdminTemplateState> v : versions) {
      Counts c = counts.getOrDefault(v.id(), Counts.ZERO);
      recent += c.recent();
      total += c.total();
      lastRead = later(lastRead, lastByTemplate.get(v.id()));
    }
    int max = versions.stream().mapToInt(v -> v.published().templateVersion()).max().orElse(0);
    Integer next = null;
    if (t.change() != null) {
      ConfigChangeOp op = t.change().getOp();
      if (op == ConfigChangeOp.CREATE || ((op == ConfigChangeOp.UPDATE || op == ConfigChangeOp.ACTIVATE) && s.active())) {
        next = max + 1;
      }
    }
    JsonNode map = s.fieldMap();
    List<String> senders = new ArrayList<>();
    if (map != null) {
      map.path("senders").forEach(x -> senders.add(x.asText()));
    }
    AdminTemplateItem.Detail d = null;
    if (detail) {
      List<AdminTemplateItem.Version> history =
          versions.stream()
              .sorted(Comparator.comparingInt((Item<AdminTemplateState> v) -> v.published().templateVersion()).reversed())
              .map(
                  v ->
                      new AdminTemplateItem.Version(
                          v.id(),
                          v.published().templateVersion(),
                          v.published().active(),
                          counts.getOrDefault(v.id(), Counts.ZERO).total()))
              .toList();
      d = new AdminTemplateItem.Detail(s.pattern(), s.fieldMap(), s.builderSpec(), history);
    }
    return new AdminTemplateItem(
        t.id(),
        t.state(),
        t.changeId(),
        s.bankCode(),
        config.bankName(s.bankCode()),
        s.messageType(),
        t.published() == null ? null : t.published().templateVersion(),
        next,
        s.active(),
        s.builderSpec() != null ? "BUILDER" : "ADVANCED",
        map == null ? null : map.path("logicalType").asText(null),
        map == null ? null : map.path("instrumentType").asText(null),
        senders,
        TemplatePatternGenerator.templateView(s.builderSpec()),
        new AdminTemplateItem.Usage(recent, total, lastRead),
        lastChange(t, versions),
        bankUnread(s.bankCode()),
        d);
  }

  /** A pending draft first; otherwise the newest release that named a version; otherwise seeded. */
  private AdminTemplateItem.LastChange lastChange(
      Item<AdminTemplateState> t, List<Item<AdminTemplateState>> versions) {
    if (t.change() != null) {
      return new AdminTemplateItem.LastChange(
          "DRAFT", t.change().getUpdatedAt(), t.change().getUpdatedBy(), null);
    }
    Map<UUID, AdminTemplateActivity.ReleaseRef> byRelease = activity.lastReleaseByTemplate();
    AdminTemplateActivity.ReleaseRef newest = null;
    OffsetDateTime createdAt = null;
    Map<UUID, OffsetDateTime> created = activity.createdByTemplate();
    for (Item<AdminTemplateState> v : versions) {
      AdminTemplateActivity.ReleaseRef ref = byRelease.get(v.id());
      if (ref != null && (newest == null || ref.version() > newest.version())) {
        newest = ref;
      }
      createdAt = later(createdAt, created.get(v.id()));
    }
    if (newest != null) {
      return new AdminTemplateItem.LastChange(
          "PUBLISHED", newest.publishedAt(), newest.publishedBy(), newest.version());
    }
    return new AdminTemplateItem.LastChange("SEEDED", createdAt, null, null);
  }

  private Long bankUnread(String bankCode) {
    Map<String, Long> unread = activity.unreadByBank();
    return unread == null ? null : unread.getOrDefault(bankCode, 0L);
  }

  private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
    if (a == null) {
      return b;
    }
    return b == null || !b.isAfter(a) ? a : b;
  }

  private static String key(Item<AdminTemplateState> t) {
    AdminTemplateState base = t.published() != null ? t.published() : t.effective();
    return base.bankCode() + " " + base.messageType();
  }

  private static Item<AdminTemplateState> require(List<Item<AdminTemplateState>> all, UUID id) {
    return all.stream()
        .filter(t -> t.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That template does not exist."));
  }

  private static BudgetInsightsException fieldInvalid(String field, String message) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_FIELD_INVALID, "Some fields are not valid.", AdminFieldError.of(field, message));
  }

  private static <T> List<T> concat(List<T> a, List<T> b) {
    List<T> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }
}
