package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.moipay.budgetinsights.config.AdminRoles;
import com.moipay.budgetinsights.domain.BiBank;
import com.moipay.budgetinsights.domain.BiBankParserTemplate;
import com.moipay.budgetinsights.domain.BiBudgetPolicy;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiConfigChange;
import com.moipay.budgetinsights.domain.BiMerchantCategoryMap;
import com.moipay.budgetinsights.domain.BiReferenceRelease;
import com.moipay.budgetinsights.domain.BiSmsSenderAllowlist;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.domain.MatchType;
import com.moipay.budgetinsights.dto.AdminBankState;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminChangeView;
import com.moipay.budgetinsights.dto.AdminChangeset;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import com.moipay.budgetinsights.dto.AdminPage;
import com.moipay.budgetinsights.dto.AdminPublishRequest;
import com.moipay.budgetinsights.dto.AdminPublishResult;
import com.moipay.budgetinsights.dto.AdminRelease;
import com.moipay.budgetinsights.dto.AdminRestoreResult;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.dto.AdminValidation;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiBankParserTemplateRepository;
import com.moipay.budgetinsights.repository.BiBankRepository;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiBudgetPolicyRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiConfigChangeRepository;
import com.moipay.budgetinsights.repository.BiMerchantCategoryMapRepository;
import com.moipay.budgetinsights.repository.BiReferenceReleaseRepository;
import com.moipay.budgetinsights.repository.BiSmsSenderAllowlistRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.AdminBankService;
import com.moipay.budgetinsights.service.AdminChangesetService;
import com.moipay.budgetinsights.service.CategorisationService;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminChangesetServiceImpl implements AdminChangesetService {

  private static final int MAX_PAGE_SIZE = 200;

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminPublishValidator validator;
  private final AdminReleaseVersion releaseVersion;
  private final AdminUsageCounts usage;
  private final AdminTemplateActivity templateActivity;
  private final AdminBankService bankRegistry;
  private final BiConfigChangeRepository changes;
  private final BiReferenceReleaseRepository releases;
  private final BiCategoryRepository categories;
  private final BiMerchantCategoryMapRepository merchantRules;
  private final BiSmsSenderAllowlistRepository senders;
  private final BiBankParserTemplateRepository templates;
  private final BiTransactionRepository transactions;
  private final BiBudgetCategoryLimitRepository limits;
  private final BiBankRepository banks;
  private final BiAccountRepository accounts;
  private final BiBudgetPolicyRepository policies;
  private final BudgetPolicyProvider policy;
  private final CategorisationService categorisation;
  private final ObjectMapper objectMapper;
  private final DataSource dataSource;
  private final ObjectProvider<MeterRegistry> meters;

  private volatile Boolean postgres;

  // --- review -------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminChangeset changeset() {
    List<AdminChangeView> out = new ArrayList<>();
    for (BiConfigChange c : ordered(changes.findAllByOrderByUpdatedAtAscIdAsc())) {
      JsonNode before = view.read(c.getBeforeState());
      JsonNode after = view.read(c.getPayload());
      out.add(
          new AdminChangeView(
              c.getId(),
              c.getEntityType(),
              c.getTargetId(),
              c.getOp(),
              AdminChangeLabels.label(c.getEntityType(), after != null ? after : before, view),
              AdminChangeLabels.summary(c.getOp(), before, after),
              c.getCreatedBy(),
              c.getUpdatedBy(),
              c.getUpdatedAt()));
    }
    return new AdminChangeset(drafts.revision(), baseVersion(), out);
  }

  @Override
  @Transactional
  public long discard(UUID changeId, Long ifMatch) {
    drafts.begin(ifMatch);
    BiConfigChange change =
        changes
            .findById(changeId)
            .orElseThrow(
                () ->
                    new BudgetInsightsException(
                        ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That pending change does not exist."));
    changes.delete(change);
    log.info(
        "Budget Insights admin discarded pending change id={} entity={} op={} target={}",
        change.getId(),
        change.getEntityType(),
        change.getOp(),
        change.getTargetId());
    return drafts.commit();
  }

  @Override
  @Transactional
  public long discardAll(Long ifMatch) {
    drafts.begin(ifMatch);
    long n = changes.count();
    changes.deleteAllInBatch();
    log.info("Budget Insights admin discarded {} pending changes", n);
    return drafts.commit();
  }

  @Override
  @Transactional(readOnly = true)
  public AdminValidation validate() {
    AdminValidation result = validator.validate(drafts.revision());
    log.info(
        "Budget Insights admin changeset validated revision={} errors={} warnings={} errorCodes={}",
        result.revision(),
        result.errors().size(),
        result.warnings().size(),
        result.errors().stream().map(AdminValidation.Issue::code).distinct().toList());
    return result;
  }

  // --- publish ------------------------------------------------------------------------------------

  @Override
  @Transactional
  public AdminPublishResult publish(AdminPublishRequest request, UUID userId) {
    if (isPostgres()) {
      releases.lockPublish();
    }
    long revision = drafts.begin(null);
    int base = baseVersion();
    log.info(
        "Budget Insights publish requested by {} revision={} base=v{} (client revision={} base=v{})",
        userId,
        revision,
        base,
        request.changesetRevision(),
        request.baseReleaseVersion());
    if (request.changesetRevision() != revision || request.baseReleaseVersion() != base) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_CHANGESET_STALE,
          "The changes or the live release moved since you reviewed them (revision "
              + revision
              + ", release v"
              + base
              + "). Review again.");
    }
    List<BiConfigChange> pending = ordered(changes.findAllByOrderByUpdatedAtAscIdAsc());
    if (pending.isEmpty()) {
      throw new BudgetInsightsException(ErrorCode.ADMIN_NOTHING_TO_PUBLISH);
    }
    AdminValidation validation = validator.validate(revision);
    if (validation.hasErrors()) {
      log.info(
          "Budget Insights publish blocked by {} error(s): {}",
          validation.errors().size(),
          validation.errors().stream().map(AdminValidation.Issue::code).distinct().toList());
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_PUBLISH_BLOCKED,
          validation.errors().size() + " problem(s) must be fixed before publishing.",
          Map.of("errors", validation.errors()));
    }
    if (!validation.warnings().isEmpty() && !Boolean.TRUE.equals(request.acknowledgeWarnings())) {
      log.info(
          "Budget Insights publish waiting for {} warning(s) to be acknowledged: {}",
          validation.warnings().size(),
          validation.warnings().stream().map(AdminValidation.Issue::code).distinct().toList());
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_WARNINGS_NOT_ACKNOWLEDGED,
          null,
          Map.of("warnings", validation.warnings()));
    }

    ArrayNode diff = objectMapper.createArrayNode();
    Map<String, Map<String, Integer>> summary = new LinkedHashMap<>();
    freeRenamedBankNames(pending);
    for (BiConfigChange change : pending) {
      UUID resultId = apply(change);
      JsonNode before = view.read(change.getBeforeState());
      JsonNode after = view.read(change.getPayload());
      ObjectNode entry = diff.addObject();
      entry.put("entityType", change.getEntityType().name());
      entry.put("targetId", change.getTargetId().toString());
      if (resultId != null && !resultId.equals(change.getTargetId())) {
        entry.put("resultId", resultId.toString());
      }
      entry.put("op", change.getOp().name());
      entry.put(
          "label",
          AdminChangeLabels.label(change.getEntityType(), after != null ? after : before, view));
      entry.set("before", before);
      entry.set("after", change.getOp() == ConfigChangeOp.DELETE ? null : after);
      summary
          .computeIfAbsent(change.getEntityType().name(), k -> new LinkedHashMap<>())
          .merge(change.getOp().name(), 1, Integer::sum);
    }

    OffsetDateTime now = OffsetDateTime.now();
    BiReferenceRelease release = new BiReferenceRelease();
    release.setVersion(base + 1);
    release.setPublishedAt(now);
    release.setPublishedBy(userId);
    release.setPublishedByRole(AdminRoles.SUPER_ADMIN);
    release.setReason(request.reason().trim());
    release.setChangeCount(pending.size());
    release.setSummary(view.write(summary));
    release.setDiff(diff.toString());
    releases.save(release);

    changes.deleteAll(pending);
    changes.flush();
    bankRegistry.confirmPublished();
    drafts.commit();
    afterCommit();
    log.info(
        "Budget Insights reference release v{} published by {} with {} change(s) {}",
        release.getVersion(),
        userId,
        pending.size(),
        summary);
    return new AdminPublishResult(
        release.getVersion(), now, pending.size(), objectMapper.valueToTree(summary));
  }

  /** Applies one change to the published tables; returns the id of the row it produced. */
  private UUID apply(BiConfigChange change) {
    JsonNode payload = view.read(change.getPayload());
    UUID id = change.getTargetId();
    return switch (change.getEntityType()) {
      case CATEGORY ->
          applyCategory(
              change.getOp(),
              id,
              view.convert(payload, AdminCategoryState.class),
              payload != null && payload.has("spendBucket"));
      case MERCHANT_RULE ->
          applyRule(change.getOp(), id, view.convert(payload, AdminMerchantRuleState.class));
      case SENDER -> applySender(change.getOp(), id, view.convert(payload, AdminSenderState.class));
      case TEMPLATE -> applyTemplate(change.getOp(), id, view.convert(payload, AdminTemplateState.class));
      case BANK ->
          applyBank(
              change.getOp(),
              id,
              view.convert(payload, AdminBankState.class),
              view.convert(view.read(change.getBeforeState()), AdminBankState.class));
      case BUDGET_POLICY -> applyPolicy(id, view.convert(payload, AdminBudgetPolicyState.class));
    };
  }

  /**
   * Bank names are unique (case-insensitive index). Two pending renames can swap names, or take a
   * name another renamed bank gives up; applied one by one they would collide. Every bank whose
   * name changes first gets a temporary name, so the final names apply in any order.
   */
  private void freeRenamedBankNames(List<BiConfigChange> pending) {
    boolean any = false;
    for (BiConfigChange change : pending) {
      if (change.getEntityType() != ConfigEntityType.BANK || change.getOp() == ConfigChangeOp.DELETE) {
        continue;
      }
      AdminBankState after = view.convert(view.read(change.getPayload()), AdminBankState.class);
      BiBank row = after == null ? null : banks.findById(after.bankCode()).orElse(null);
      if (row != null && !row.getBankName().equals(after.bankName())) {
        row.setBankName("~renaming " + row.getBankCode() + "~");
        banks.save(row);
        any = true;
      }
    }
    if (any) {
      banks.flush();
    }
  }

  /** Rename, turn on/off or delete a bank. Its code never changes. */
  private UUID applyBank(ConfigChangeOp op, UUID id, AdminBankState s, AdminBankState before) {
    String code = s != null ? s.bankCode() : before == null ? null : before.bankCode();
    BiBank row = code == null ? null : banks.findById(code).orElse(null);
    if (row == null && op == ConfigChangeOp.CREATE && s != null) {
      // Only a restore drafts a bank CREATE: it brings back a bank a later release deleted.
      row = BiBank.builder().bankCode(code).createdAt(OffsetDateTime.now()).isPending(false).build();
    }
    if (row == null) {
      throw missing(ConfigEntityType.BANK, id);
    }
    if (op == ConfigChangeOp.DELETE) {
      senders.flush();
      templates.flush();
      boolean used =
          senders.findAll().stream().anyMatch(x -> code.equals(x.getBankCode()))
              || templates.findAll().stream().anyMatch(t -> code.equals(t.getBankCode()))
              || accounts.countByBankCode(code) > 0;
      if (used) {
        throw new BudgetInsightsException(
            ErrorCode.ADMIN_PUBLISH_BLOCKED,
            "Bank " + code + " waiting to be deleted is now in use. Turn it off instead.");
      }
      banks.delete(row);
      banks.flush();
      return id;
    }
    row.setBankName(s.bankName());
    row.setIsActive(s.active());
    row.setUpdatedAt(OffsetDateTime.now());
    try {
      banks.saveAndFlush(row);
    } catch (DataIntegrityViolationException clash) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_PUBLISH_BLOCKED,
          "Bank name " + s.bankName() + " is already used by another bank. Change one of them and"
              + " review again.");
    }
    return id;
  }

  private UUID applyPolicy(UUID id, AdminBudgetPolicyState s) {
    BiBudgetPolicy row =
        policies.findById(BiBudgetPolicy.ID)
            .orElseGet(() -> BiBudgetPolicy.builder().id(BiBudgetPolicy.ID).build());
    row.setNeedsPct((short) s.needsPct());
    row.setWantsPct((short) s.wantsPct());
    row.setSavingsPct((short) s.savingsPct());
    row.setTrailingMonths((short) s.trailingMonths());
    row.setDefaultAlertPct((short) s.defaultAlertPct());
    row.setPaceTolerancePct((short) s.paceTolerancePct());
    row.setAlertCategoryApproaching(s.alertCategoryApproaching());
    row.setAlertCategoryExceeded(s.alertCategoryExceeded());
    row.setAlertBudgetExceeded(s.alertBudgetExceeded());
    row.setUpdatedAt(OffsetDateTime.now());
    policies.saveAndFlush(row);
    return id;
  }

  /**
   * @param hasBucket false for a draft saved before V1.8.0, whose JSON has no spendBucket: the
   *     category keeps the bucket it has instead of losing it
   */
  private UUID applyCategory(ConfigChangeOp op, UUID id, AdminCategoryState s, boolean hasBucket) {
    if (op == ConfigChangeOp.DELETE) {
      categories.flush();
      long used =
          transactions.countReferencingCategory(id)
              + limits.countByCategoryId(id)
              + merchantRules.countByCategoryId(id);
      if (used > 0) {
        throw new BudgetInsightsException(
            ErrorCode.ADMIN_PUBLISH_BLOCKED,
            "A category waiting to be deleted is now in use. Deactivate it instead.");
      }
      categories.deleteById(id);
      return id;
    }
    BiCategory row =
        op == ConfigChangeOp.CREATE
            ? BiCategory.builder().id(id).build()
            : categories.findById(id).orElseThrow(() -> missing(ConfigEntityType.CATEGORY, id));
    row.setCode(s.code());
    row.setNameEn(s.nameEn());
    row.setNameSi(s.nameSi());
    row.setNameTa(s.nameTa());
    row.setIconKey(s.iconKey());
    row.setColourHex(s.colourHex());
    row.setSortOrder((short) s.sortOrder());
    row.setIsActive(s.active());
    if (hasBucket) {
      row.setSpendBucket(s.spendBucket());
    }
    categories.saveAndFlush(row);
    return id;
  }

  private UUID applyRule(ConfigChangeOp op, UUID id, AdminMerchantRuleState s) {
    if (op == ConfigChangeOp.DELETE) {
      merchantRules.deleteById(id);
      merchantRules.flush();
      return id;
    }
    BiMerchantCategoryMap row =
        op == ConfigChangeOp.CREATE
            ? BiMerchantCategoryMap.builder().id(id).build()
            : merchantRules.findById(id).orElseThrow(() -> missing(ConfigEntityType.MERCHANT_RULE, id));
    row.setMerchantPattern(s.pattern());
    row.setMatchType(MatchType.valueOf(s.matchType()));
    row.setCategoryId(s.categoryId());
    row.setPriority((short) s.priority());
    row.setIsActive(s.active());
    row.setRuleSpec(view.write(s.ruleSpec()));
    row.setUpdatedAt(OffsetDateTime.now());
    merchantRules.saveAndFlush(row);
    return id;
  }

  private UUID applySender(ConfigChangeOp op, UUID id, AdminSenderState s) {
    if (op == ConfigChangeOp.DELETE) {
      senders.deleteById(id);
      senders.flush();
      return id;
    }
    BiSmsSenderAllowlist row =
        op == ConfigChangeOp.CREATE
            ? BiSmsSenderAllowlist.builder().id(id).build()
            : senders.findById(id).orElseThrow(() -> missing(ConfigEntityType.SENDER, id));
    row.setBankCode(s.bankCode());
    row.setSenderId(s.senderId());
    row.setCountryCode(s.countryCode());
    row.setIsActive(s.active());
    row.setUpdatedAt(OffsetDateTime.now());
    senders.saveAndFlush(row);
    return id;
  }

  /**
   * Templates are never edited in place: {@code bi_transaction.parser_template_id} points at the
   * row that parsed each message. An edit inserts version N+1 and retires N; a retire only turns
   * the row off. Nothing is ever deleted.
   */
  private UUID applyTemplate(ConfigChangeOp op, UUID id, AdminTemplateState s) {
    Optional<BiBankParserTemplate> existing = templates.findById(id);
    if (op == ConfigChangeOp.RETIRE || (op == ConfigChangeOp.UPDATE && !s.active())) {
      BiBankParserTemplate row = existing.orElseThrow(() -> missing(ConfigEntityType.TEMPLATE, id));
      row.setIsActive(false);
      templates.saveAndFlush(row);
      return id;
    }
    existing.ifPresent(
        old -> {
          old.setIsActive(false);
          templates.saveAndFlush(old);
        });
    UUID newId = op == ConfigChangeOp.CREATE ? id : UUID.randomUUID();
    BiBankParserTemplate row =
        BiBankParserTemplate.builder()
            .id(newId)
            .bankCode(s.bankCode())
            .messageType(s.messageType())
            .pattern(s.pattern())
            .fieldMap(view.write(s.fieldMap()))
            .templateVersion(templates.findMaxVersion(s.bankCode(), s.messageType()) + 1)
            .isActive(true)
            .builderSpec(view.write(s.builderSpec()))
            .build();
    templates.saveAndFlush(row);
    return newId;
  }

  private void afterCommit() {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      refreshCaches();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            refreshCaches();
          }
        });
  }

  /** After a publish commits: this instance picks the release up now, others within 30 s. */
  private void refreshCaches() {
    categorisation.refresh();
    policy.evict();
    usage.evict();
    releaseVersion.evict();
    templateActivity.evict();
    log.info("Budget Insights reference caches refreshed after publish");
    MeterRegistry registry = meters.getIfAvailable();
    if (registry != null) {
      registry.counter("bi_reference_publish_total").increment();
      registry.counter("bi_categorisation_cache_reload_total").increment();
    }
  }

  // --- history ------------------------------------------------------------------------------------

  @Override
  @Transactional(readOnly = true)
  public AdminPage<AdminRelease> releases(int page, int size) {
    int p = Math.max(0, page);
    int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    Page<BiReferenceRelease> rows = releases.findAllByOrderByVersionDesc(PageRequest.of(p, s));
    return new AdminPage<>(
        rows.getContent().stream().map(r -> toRelease(r, false)).toList(),
        p,
        s,
        rows.getTotalElements(),
        rows.getTotalPages());
  }

  @Override
  @Transactional(readOnly = true)
  public AdminRelease release(int version) {
    return releases
        .findById(version)
        .map(r -> toRelease(r, true))
        .orElseThrow(
            () ->
                new BudgetInsightsException(
                    ErrorCode.ADMIN_CONFIG_NOT_FOUND, "Release v" + version + " does not exist."));
  }

  /**
   * Creates the drafts that bring the configuration back to what release {@code version} left
   * live. Walks the later releases newest first and keeps, per item, the state it had before the
   * earliest of them — which is its state at {@code version}.
   */
  @Override
  @Transactional
  public AdminRestoreResult restore(int version, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    if (releases.findById(version).isEmpty()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_CONFIG_NOT_FOUND, "Release v" + version + " does not exist.");
    }
    if (changes.count() > 0) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_CHANGESET_STALE,
          "Publish or discard the pending changes before restoring an earlier release.");
    }
    int current = baseVersion();
    if (version >= current) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_NOTHING_TO_PUBLISH, "v" + version + " is already the live release.");
    }

    Map<String, JsonNode> target = new LinkedHashMap<>();
    Map<String, ConfigEntityType> types = new LinkedHashMap<>();
    Map<String, UUID> ids = new LinkedHashMap<>();
    List<BiReferenceRelease> later =
        releases.findAll().stream()
            .filter(r -> r.getVersion() > version)
            .sorted(Comparator.comparing(BiReferenceRelease::getVersion).reversed())
            .toList();
    for (BiReferenceRelease r : later) {
      for (JsonNode entry : view.read(r.getDiff())) {
        ConfigEntityType type = ConfigEntityType.valueOf(entry.path("entityType").asText());
        JsonNode before = entry.get("before");
        JsonNode state = before != null && !before.isNull() ? before : entry.get("after");
        String key = restoreKey(type, entry.path("targetId").asText(), state);
        target.put(key, before == null || before.isNull() ? null : before);
        types.put(key, type);
        ids.put(key, UUID.fromString(entry.path("targetId").asText()));
      }
    }

    for (Map.Entry<String, JsonNode> e : target.entrySet()) {
      ConfigEntityType type = types.get(e.getKey());
      UUID id = ids.get(e.getKey());
      JsonNode desired = e.getValue();
      switch (type) {
        case CATEGORY -> restoreSimple(type, id, desired, AdminCategoryState.class, userId);
        case MERCHANT_RULE -> restoreSimple(type, id, desired, AdminMerchantRuleState.class, userId);
        case SENDER -> restoreSimple(type, id, desired, AdminSenderState.class, userId);
        case TEMPLATE -> restoreTemplate(e.getKey(), desired, userId);
        case BANK -> restoreSimple(type, id, desired, AdminBankState.class, userId);
        case BUDGET_POLICY -> restoreSimple(type, id, desired, AdminBudgetPolicyState.class, userId);
      }
    }
    int created = (int) changes.count();
    long revision = drafts.commit();
    log.info(
        "Budget Insights restore of v{} drafted by {}: {} change(s) now pending, revision={}",
        version,
        userId,
        created,
        revision);
    return new AdminRestoreResult(revision, created, version);
  }

  private <T> void restoreSimple(
      ConfigEntityType type, UUID id, JsonNode desired, Class<T> stateType, UUID userId) {
    List<? extends Item<T>> items = itemsOf(type);
    Optional<? extends Item<T>> current = items.stream().filter(i -> i.id().equals(id)).findFirst();
    T published = current.map(Item::published).orElse(null);
    if (desired == null) {
      if (published == null) {
        return;
      }
      if (type == ConfigEntityType.CATEGORY) {
        UUID cid = id;
        boolean used =
            transactions.countReferencingCategory(cid) + limits.countByCategoryId(cid) > 0;
        AdminCategoryState c = (AdminCategoryState) published;
        if (used) {
          drafts.draft(type, id, ConfigChangeOp.DEACTIVATE, published, c.withActive(false), userId);
          return;
        }
      }
      drafts.draft(type, id, ConfigChangeOp.DELETE, published, null, userId);
      return;
    }
    if (type == ConfigEntityType.CATEGORY
        && published != null
        && desired.isObject()
        && !desired.has("spendBucket")) {
      // A release from before V1.8.0 recorded no bucket; restoring it keeps today's bucket.
      ObjectNode withBucket = ((ObjectNode) desired).deepCopy();
      withBucket.put("spendBucket", ((AdminCategoryState) published).spendBucket());
      desired = withBucket;
    }
    T state = view.convert(desired, stateType);
    ConfigChangeOp op = published == null ? ConfigChangeOp.CREATE : ConfigChangeOp.UPDATE;
    drafts.draft(type, id, op, published, state, userId);
  }

  private void restoreTemplate(String key, JsonNode desired, UUID userId) {
    Optional<Item<AdminTemplateState>> live =
        view.currentTemplates().stream()
            .filter(t -> t.published() != null && t.published().active())
            .filter(t -> key.equals("TEMPLATE|" + bankType(view.tree(t.published()))))
            .findFirst();
    if (desired == null || !desired.path("active").asBoolean(true)) {
      live.ifPresent(
          t ->
              drafts.draft(
                  ConfigEntityType.TEMPLATE,
                  t.id(),
                  ConfigChangeOp.RETIRE,
                  t.published(),
                  t.published().withActive(false),
                  userId));
      return;
    }
    AdminTemplateState state = view.convert(desired, AdminTemplateState.class);
    if (live.isPresent()) {
      AdminTemplateState now = live.get().published();
      boolean same =
          Objects.equals(now.pattern(), state.pattern())
              && Objects.equals(now.fieldMap(), state.fieldMap());
      if (!same) {
        drafts.draft(
            ConfigEntityType.TEMPLATE, live.get().id(), ConfigChangeOp.UPDATE, now, state, userId);
      }
      return;
    }
    drafts.draft(
        ConfigEntityType.TEMPLATE, UUID.randomUUID(), ConfigChangeOp.CREATE, null, state, userId);
  }

  @SuppressWarnings("unchecked")
  private <T> List<Item<T>> itemsOf(ConfigEntityType type) {
    return (List<Item<T>>)
        (List<?>)
            switch (type) {
              case CATEGORY -> view.categories();
              case MERCHANT_RULE -> view.merchantRules();
              case SENDER -> view.senders();
              case TEMPLATE -> view.templates();
              case BANK -> view.banks();
              case BUDGET_POLICY -> view.budgetPolicy();
            };
  }

  private static String restoreKey(ConfigEntityType type, String targetId, JsonNode state) {
    // Template ids change with every version, so a template is followed by bank and message type.
    return type == ConfigEntityType.TEMPLATE
        ? "TEMPLATE|" + bankType(state)
        : type.name() + "|" + targetId;
  }

  private static String bankType(JsonNode state) {
    return state == null
        ? ""
        : state.path("bankCode").asText() + "|" + state.path("messageType").asText();
  }

  // --- helpers ------------------------------------------------------------------------------------

  /**
   * Apply order: bank edits, new and edited categories, rules, senders, templates, the budget
   * policy, then category removals and bank deletions (after the senders that named them are gone).
   */
  private static List<BiConfigChange> ordered(List<BiConfigChange> in) {
    List<BiConfigChange> out = new ArrayList<>(in);
    out.sort(Comparator.comparingInt(AdminChangesetServiceImpl::phase));
    return out;
  }

  private static int phase(BiConfigChange c) {
    return switch (c.getEntityType()) {
      case CATEGORY ->
          c.getOp() == ConfigChangeOp.DEACTIVATE || c.getOp() == ConfigChangeOp.DELETE ? 6 : 0;
      case MERCHANT_RULE -> 1;
      case SENDER -> 2;
      case TEMPLATE -> 3;
      case BANK -> c.getOp() == ConfigChangeOp.DELETE ? 7 : -1;
      case BUDGET_POLICY -> 5;
    };
  }

  private int baseVersion() {
    return Math.max(1, releases.findCurrentVersion());
  }

  private AdminRelease toRelease(BiReferenceRelease r, boolean withDiff) {
    return new AdminRelease(
        r.getVersion(),
        r.getPublishedAt(),
        AdminRelease.Publisher.of(r.getPublishedBy()),
        r.getPublishedByRole(),
        r.getReason(),
        r.getChangeCount() == null ? 0 : r.getChangeCount(),
        view.read(r.getSummary()),
        withDiff ? view.read(r.getDiff()) : null);
  }

  private static BudgetInsightsException missing(ConfigEntityType type, UUID id) {
    return new BudgetInsightsException(
        ErrorCode.ADMIN_PUBLISH_BLOCKED,
        type.label() + " " + id + " no longer exists. Discard that change and review again.");
  }

  private boolean isPostgres() {
    Boolean known = postgres;
    if (known == null) {
      try (Connection c = dataSource.getConnection()) {
        known = c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
      } catch (SQLException ex) {
        known = Boolean.FALSE;
      }
      postgres = known;
    }
    return known;
  }
}
