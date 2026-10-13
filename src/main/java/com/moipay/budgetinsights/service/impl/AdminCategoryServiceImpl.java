package com.moipay.budgetinsights.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.config.InsightsProperties;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.dto.AdminCategoryInput;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminConfigItem;
import com.moipay.budgetinsights.dto.AdminConfigList;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiBudgetCategoryLimitRepository;
import com.moipay.budgetinsights.repository.BiTransactionRepository;
import com.moipay.budgetinsights.service.AdminCategoryService;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import com.moipay.budgetinsights.service.impl.AdminUsageCounts.Counts;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCategoryServiceImpl implements AdminCategoryService {

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminUsageCounts usage;
  private final AdminReleaseVersion releases;
  private final BiTransactionRepository transactions;
  private final BiBudgetCategoryLimitRepository limits;
  private final InsightsProperties properties;

  @Override
  @Transactional(readOnly = true)
  public AdminConfigList<AdminConfigItem<AdminCategoryState>> list(String q, String active) {
    Map<UUID, Counts> txns = usage.transactionsByCategory();
    Map<UUID, Long> limitCounts = usage.budgetLimitsByCategory();
    List<Item<AdminMerchantRuleState>> rules = view.merchantRules();
    List<AdminConfigItem<AdminCategoryState>> items =
        view.categories().stream()
            .filter(i -> AdminListFilters.matchesActive(active, i.effective().active()))
            .filter(
                i ->
                    AdminListFilters.matchesText(
                        q,
                        i.effective().code(),
                        i.effective().nameEn(),
                        i.effective().nameSi(),
                        i.effective().nameTa()))
            .map(i -> toItem(i, txns, limitCounts, rules))
            .toList();
    return new AdminConfigList<>(items, drafts.revision(), releases.current());
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminCategoryState>> create(
      AdminCategoryInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    AdminCategoryState desired = toState(input, null);
    requireIconKey(desired.iconKey());
    requireUniqueCode(desired.code(), null);
    UUID id = UUID.randomUUID();
    drafts.draft(ConfigEntityType.CATEGORY, id, ConfigChangeOp.CREATE, null, desired, userId);
    log.info(
        "bi-admin category create drafted id={} code={} bucket={} active={} by={}",
        id, desired.code(), desired.spendBucket(), desired.active(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminCategoryState>> update(
      UUID id, AdminCategoryInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminCategoryState> item = require(id);
    requireChangeable(item);
    AdminCategoryState desired = toState(input, item.effective().spendBucket());
    if (item.published() != null && !item.published().code().equals(desired.code())) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_SYSTEM_ITEM_LOCKED,
          "A published category code cannot change. Create a new category instead.");
    }
    requireIconKey(desired.iconKey());
    requireUniqueCode(desired.code(), id);
    ConfigChangeOp op =
        item.published() == null ? ConfigChangeOp.CREATE : opFor(item.published(), desired);
    drafts.draft(ConfigEntityType.CATEGORY, id, op, item.published(), desired, userId);
    log.info(
        "bi-admin category {} drafted id={} code={} bucket={} active={} by={}",
        op, id, desired.code(), desired.spendBucket(), desired.active(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminCategoryState>> deactivate(
      UUID id, UUID moveRulesTo, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminCategoryState> item = require(id);
    requireChangeable(item);
    if (moveRulesTo != null) {
      Item<AdminCategoryState> target = require(moveRulesTo);
      if (moveRulesTo.equals(id) || !target.survives() || !target.effective().active()) {
        throw BudgetInsightsException.withDetails(
            ErrorCode.ADMIN_FIELD_INVALID,
            "Merchant rules can only move to another active category.",
            AdminFieldError.of("moveMerchantRulesTo", "must be another active category"));
      }
    }
    // Its rules move with it or turn off, so no active rule is left pointing at an inactive
    // category (that is a blocking publish check).
    for (Item<AdminMerchantRuleState> rule : view.merchantRules()) {
      if (!rule.survives() || !id.equals(rule.effective().categoryId())) {
        continue;
      }
      AdminMerchantRuleState moved =
          moveRulesTo != null
              ? rule.effective().withCategory(moveRulesTo)
              : rule.effective().withActive(false);
      ConfigChangeOp op = rule.published() == null ? ConfigChangeOp.CREATE : ConfigChangeOp.UPDATE;
      drafts.draft(ConfigEntityType.MERCHANT_RULE, rule.id(), op, rule.published(), moved, userId);
      log.info(
          "bi-admin category deactivate: rule {} {} (category {})",
          rule.id(),
          moveRulesTo != null ? "moved to " + moveRulesTo : "turned off",
          id);
    }
    ConfigChangeOp op =
        item.published() == null ? ConfigChangeOp.CREATE : ConfigChangeOp.DEACTIVATE;
    drafts.draft(
        ConfigEntityType.CATEGORY,
        id,
        op,
        item.published(),
        item.effective().withActive(false),
        userId);
    log.info(
        "bi-admin category deactivate drafted id={} code={} moveRulesTo={} by={}",
        id, item.effective().code(), moveRulesTo, userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminCategoryState>> activate(
      UUID id, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminCategoryState> item = require(id);
    requireChangeable(item);
    if (item.change() != null && item.change().getOp() == ConfigChangeOp.DEACTIVATE) {
      undoRuleMoves(id, userId);
    }
    ConfigChangeOp op = item.published() == null ? ConfigChangeOp.CREATE : ConfigChangeOp.ACTIVATE;
    drafts.draft(
        ConfigEntityType.CATEGORY,
        id,
        op,
        item.published(),
        item.effective().withActive(true),
        userId);
    log.info("bi-admin category activate drafted id={} code={} by={}", id, item.effective().code(), userId);
    return result(id);
  }

  /**
   * Re-activating a category whose deactivation is still a draft also withdraws the rule drafts that
   * deactivation made (rules moved to another category or turned off), so the changeset is left as
   * it was before the deactivate. A rule draft that changed anything else is the admin's own edit
   * and is kept.
   */
  private void undoRuleMoves(UUID id, UUID userId) {
    for (Item<AdminMerchantRuleState> rule : view.merchantRules()) {
      AdminMerchantRuleState published = rule.published();
      if (rule.change() == null || published == null || !id.equals(published.categoryId())) {
        continue;
      }
      AdminMerchantRuleState onlyMoved =
          rule.effective().withCategory(published.categoryId()).withActive(published.active());
      if (onlyMoved.equals(published)) {
        drafts.draft(
            ConfigEntityType.MERCHANT_RULE, rule.id(), ConfigChangeOp.UPDATE, published, published, userId);
      }
    }
  }

  @Override
  @Transactional(readOnly = true)
  public DeleteCheck usage(UUID id) {
    Item<AdminCategoryState> item = require(id);
    long rules =
        view.merchantRules().stream()
            .filter(r -> r.survives() && id.equals(r.effective().categoryId()))
            .count();
    if (item.published() == null) {
      return new DeleteCheck(0, rules, 0, rules == 0);
    }
    long txns = transactions.countReferencingCategory(id);
    long limitCount = limits.countByCategoryId(id);
    boolean deletable = txns == 0 && rules == 0 && limitCount == 0 && !isOther(item);
    return new DeleteCheck(txns, rules, limitCount, deletable);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminCategoryState>> delete(
      UUID id, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminCategoryState> item = require(id);
    requireChangeable(item);
    DeleteCheck check = usage(id);
    if (!check.deletable()) {
      log.info(
          "bi-admin category delete refused id={} code={} transactions={} rules={} limits={}",
          id,
          item.effective().code(),
          check.transactionsTotal(),
          check.merchantRules(),
          check.budgetLimits());
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CATEGORY_IN_USE,
          "This category is in use. Deactivate it instead, and move its merchant rules if needed.",
          Map.of(
              "usage",
              Map.of(
                  "transactionsTotal", check.transactionsTotal(),
                  "merchantRules", check.merchantRules(),
                  "budgetLimits", check.budgetLimits(),
                  "deletable", false)));
    }
    drafts.draft(ConfigEntityType.CATEGORY, id, ConfigChangeOp.DELETE, item.published(), null, userId);
    log.info("bi-admin category delete drafted id={} code={} by={}", id, item.effective().code(), userId);
    return result(id);
  }

  // ---------------------------------------------------------------------------------------------

  private AdminWriteResult<AdminConfigItem<AdminCategoryState>> result(UUID id) {
    long revision = drafts.commit();
    Optional<Item<AdminCategoryState>> item = find(id);
    return new AdminWriteResult<>(
        item.map(
                i ->
                    toItem(
                        i,
                        usage.transactionsByCategory(),
                        usage.budgetLimitsByCategory(),
                        view.merchantRules()))
            .orElse(null),
        revision);
  }

  private AdminConfigItem<AdminCategoryState> toItem(
      Item<AdminCategoryState> i,
      Map<UUID, Counts> txns,
      Map<UUID, Long> limitCounts,
      List<Item<AdminMerchantRuleState>> rules) {
    Counts c = txns.getOrDefault(i.id(), Counts.ZERO);
    long ruleCount =
        rules.stream().filter(r -> r.survives() && i.id().equals(r.effective().categoryId())).count();
    return new AdminConfigItem<>(
        i.id(),
        i.state(),
        i.changeId(),
        i.published(),
        i.effective(),
        isOther(i),
        new Usage(c.recent(), c.total(), ruleCount, limitCounts.getOrDefault(i.id(), 0L)));
  }

  private Optional<Item<AdminCategoryState>> find(UUID id) {
    return view.categories().stream().filter(i -> i.id().equals(id)).findFirst();
  }

  private Item<AdminCategoryState> require(UUID id) {
    return find(id)
        .orElseThrow(
            () ->
                new BudgetInsightsException(
                    ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That category does not exist."));
  }

  private static boolean isOther(Item<AdminCategoryState> i) {
    AdminCategoryState base = i.published() != null ? i.published() : i.effective();
    return AdminCategoryState.OTHER.equals(base.code());
  }

  private static void requireChangeable(Item<AdminCategoryState> item) {
    if (isOther(item)) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_SYSTEM_ITEM_LOCKED,
          "OTHER is where uncategorised transactions go. It cannot be changed or removed.");
    }
    if (!item.survives()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID,
          "This category is waiting to be deleted. Discard that change first.");
    }
  }

  private void requireUniqueCode(String code, UUID self) {
    boolean clash =
        view.categories().stream()
            .anyMatch(i -> !i.id().equals(self) && i.effective().code().equals(code));
    if (clash) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "A category with the code " + code + " already exists.",
          AdminFieldError.of("code", "already used by another category or pending draft"));
    }
  }

  private void requireIconKey(String iconKey) {
    if (!properties.getAdmin().getIconKeys().contains(iconKey)) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "The app has no icon called " + iconKey + ".",
          AdminFieldError.of("iconKey", "not one of the app's icons"));
    }
  }

  /** {@code currentBucket} is kept when the input leaves {@code spendBucket} out. */
  private static AdminCategoryState toState(AdminCategoryInput in, String currentBucket) {
    String bucket =
        in.spendBucket() == null
            ? currentBucket
            : "NONE".equals(in.spendBucket()) ? null : in.spendBucket();
    return new AdminCategoryState(
        in.code().trim().toUpperCase(Locale.ROOT),
        in.nameEn().trim(),
        blankToNull(in.nameSi()),
        blankToNull(in.nameTa()),
        in.iconKey(),
        blankToNull(in.colourHex()),
        in.sortOrder(),
        in.active(),
        bucket);
  }

  private static ConfigChangeOp opFor(AdminCategoryState published, AdminCategoryState desired) {
    if (published.active() != desired.active()
        && Objects.equals(published.withActive(desired.active()), desired)) {
      return desired.active() ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE;
    }
    return ConfigChangeOp.UPDATE;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
