package com.moipay.budgetinsights.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moipay.budgetinsights.domain.BiBank;
import com.moipay.budgetinsights.domain.BiBankParserTemplate;
import com.moipay.budgetinsights.domain.BiCategory;
import com.moipay.budgetinsights.domain.BiConfigChange;
import com.moipay.budgetinsights.domain.BiMerchantCategoryMap;
import com.moipay.budgetinsights.domain.BiSmsSenderAllowlist;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.domain.ConfigItemState;
import com.moipay.budgetinsights.dto.AdminBankState;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.AdminCategoryState;
import com.moipay.budgetinsights.dto.AdminMerchantRuleSpec;
import com.moipay.budgetinsights.dto.AdminMerchantRuleState;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.repository.BiBankParserTemplateRepository;
import com.moipay.budgetinsights.repository.BiBankRepository;
import com.moipay.budgetinsights.repository.BiCategoryRepository;
import com.moipay.budgetinsights.repository.BiConfigChangeRepository;
import com.moipay.budgetinsights.repository.BiMerchantCategoryMapRepository;
import com.moipay.budgetinsights.repository.BiSmsSenderAllowlistRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The admin's effective view: every published reference row with its pending change applied,
 * plus drafts of new items.
 *
 * <p>Every configuration list, uniqueness check, rule test and publish validation reads this, so
 * what a Super Admin sees before publishing is exactly what goes live.
 */
@Component
@RequiredArgsConstructor
public class AdminEffectiveView {

  /**
   * One item in the effective view.
   *
   * @param published {@code null} for a draft that was never published
   * @param effective what will be live after publish; for a pending delete, the published values
   */
  public record Item<T>(UUID id, T published, T effective, BiConfigChange change) {

    public ConfigItemState state() {
      return ConfigItemState.of(change == null ? null : change.getOp());
    }

    /** True unless the item is about to be deleted. */
    public boolean survives() {
      return change == null || change.getOp() != ConfigChangeOp.DELETE;
    }

    public UUID changeId() {
      return change == null ? null : change.getId();
    }
  }

  private final BiCategoryRepository categories;
  private final BiMerchantCategoryMapRepository merchantRules;
  private final BiSmsSenderAllowlistRepository senders;
  private final BiBankParserTemplateRepository templates;
  private final BiConfigChangeRepository changes;
  private final BiBankRepository banks;
  private final BudgetPolicyProvider policy;
  private final ObjectMapper objectMapper;

  /** Target id of the one budget-policy item in {@code bi_config_change}. */
  public static final UUID BUDGET_POLICY_ID = UUID.fromString("b0d9e7a1-0000-4000-8000-000000000001");

  /** A bank's target id in {@code bi_config_change}: derived from its code, which never changes. */
  public static UUID bankId(String bankCode) {
    return UUID.nameUUIDFromBytes(("BANK:" + bankCode).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Every bank in the registry with its pending edit applied, by name. Banks added through "Add
   * bank SMS sender" are rows already (pending until that sender is published); they appear here
   * as published items whose edits apply directly.
   */
  public List<Item<AdminBankState>> banks() {
    Map<UUID, AdminBankState> published = new LinkedHashMap<>();
    banks.findAll().stream()
        .sorted(Comparator.comparing(BiBank::getBankName, String.CASE_INSENSITIVE_ORDER))
        .forEach(b -> published.put(bankId(b.getBankCode()), toState(b)));
    return merge(ConfigEntityType.BANK, published, AdminBankState.class);
  }

  /** The budget policy as one item: the published row (or config defaults) and any pending edit. */
  public List<Item<AdminBudgetPolicyState>> budgetPolicy() {
    Map<UUID, AdminBudgetPolicyState> published = new LinkedHashMap<>();
    published.put(BUDGET_POLICY_ID, policy.load());
    return merge(ConfigEntityType.BUDGET_POLICY, published, AdminBudgetPolicyState.class);
  }

  public AdminBankState toState(BiBank b) {
    return new AdminBankState(b.getBankCode(), b.getBankName(), Boolean.TRUE.equals(b.getIsActive()));
  }

  public List<Item<AdminCategoryState>> categories() {
    Map<UUID, AdminCategoryState> published = new LinkedHashMap<>();
    categories.findAll().stream()
        .sorted(Comparator.comparing(BiCategory::getSortOrder).thenComparing(BiCategory::getCode))
        .forEach(c -> published.put(c.getId(), toState(c)));
    return merge(ConfigEntityType.CATEGORY, published, AdminCategoryState.class);
  }

  public List<Item<AdminMerchantRuleState>> merchantRules() {
    Map<UUID, AdminMerchantRuleState> published = new LinkedHashMap<>();
    merchantRules.findAll().stream()
        .sorted(
            Comparator.comparing(BiMerchantCategoryMap::getPriority)
                .thenComparing(BiMerchantCategoryMap::getMatchType)
                .thenComparing(BiMerchantCategoryMap::getMerchantPattern))
        .forEach(r -> published.put(r.getId(), toState(r)));
    return merge(ConfigEntityType.MERCHANT_RULE, published, AdminMerchantRuleState.class);
  }

  public List<Item<AdminSenderState>> senders() {
    Map<UUID, AdminSenderState> published = new LinkedHashMap<>();
    senders.findAll().stream()
        .sorted(
            Comparator.comparing(BiSmsSenderAllowlist::getBankCode)
                .thenComparing(BiSmsSenderAllowlist::getSenderId))
        .forEach(s -> published.put(s.getId(), toState(s)));
    return merge(ConfigEntityType.SENDER, published, AdminSenderState.class);
  }

  /** Every stored template version plus drafts of new templates. */
  public List<Item<AdminTemplateState>> templates() {
    Map<UUID, AdminTemplateState> published = new LinkedHashMap<>();
    templates.findAll().stream()
        .sorted(
            Comparator.comparing(BiBankParserTemplate::getBankCode)
                .thenComparing(BiBankParserTemplate::getMessageType)
                .thenComparing(BiBankParserTemplate::getTemplateVersion, Comparator.reverseOrder()))
        .forEach(t -> published.put(t.getId(), toState(t)));
    return merge(ConfigEntityType.TEMPLATE, published, AdminTemplateState.class);
  }

  /**
   * One template per bank and message type: the version that is (or will be) active, otherwise
   * the newest retired one; plus drafts of new templates.
   */
  public List<Item<AdminTemplateState>> currentTemplates() {
    Map<String, Item<AdminTemplateState>> byKey = new LinkedHashMap<>();
    for (Item<AdminTemplateState> item : templates()) {
      AdminTemplateState base = item.published() != null ? item.published() : item.effective();
      String key = base.bankCode() + "|" + base.messageType();
      Item<AdminTemplateState> held = byKey.get(key);
      if (held == null || preferred(item, held)) {
        byKey.put(key, item);
      }
    }
    return new ArrayList<>(byKey.values());
  }

  public static <T> Map<UUID, Item<T>> byId(List<Item<T>> items) {
    Map<UUID, Item<T>> out = new LinkedHashMap<>();
    items.forEach(i -> out.put(i.id(), i));
    return out;
  }

  // --- state mapping ----------------------------------------------------------------------------

  public AdminCategoryState toState(BiCategory c) {
    return new AdminCategoryState(
        c.getCode(),
        c.getNameEn(),
        c.getNameSi(),
        c.getNameTa(),
        c.getIconKey(),
        c.getColourHex(),
        c.getSortOrder() == null ? 0 : c.getSortOrder(),
        Boolean.TRUE.equals(c.getIsActive()),
        c.getSpendBucket());
  }

  public AdminMerchantRuleState toState(BiMerchantCategoryMap r) {
    return new AdminMerchantRuleState(
        r.getMatchType().name(),
        r.getMerchantPattern(),
        r.getCategoryId(),
        r.getPriority() == null ? 100 : r.getPriority(),
        Boolean.TRUE.equals(r.getIsActive()),
        convert(read(r.getRuleSpec()), AdminMerchantRuleSpec.class));
  }

  public AdminSenderState toState(BiSmsSenderAllowlist s) {
    return new AdminSenderState(
        s.getBankCode(), s.getSenderId(), s.getCountryCode(), Boolean.TRUE.equals(s.getIsActive()));
  }

  public AdminTemplateState toState(BiBankParserTemplate t) {
    return new AdminTemplateState(
        t.getBankCode(),
        t.getMessageType(),
        t.getPattern(),
        read(t.getFieldMap()),
        t.getTemplateVersion() == null ? 1 : t.getTemplateVersion(),
        Boolean.TRUE.equals(t.getIsActive()),
        read(t.getBuilderSpec()));
  }

  // --- JSON -------------------------------------------------------------------------------------

  public JsonNode read(String text) {
    if (text == null) {
      return null;
    }
    try {
      return objectMapper.readTree(text);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored admin JSON could not be read", ex);
    }
  }

  public String write(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Admin value could not be written as JSON", ex);
    }
  }

  public JsonNode tree(Object value) {
    return value == null ? null : objectMapper.valueToTree(value);
  }

  public <T> T convert(JsonNode node, Class<T> type) {
    if (node == null || node.isNull()) {
      return null;
    }
    try {
      return objectMapper.treeToValue(node, type);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Stored admin JSON has an unexpected shape", ex);
    }
  }

  // ---------------------------------------------------------------------------------------------

  private <T> List<Item<T>> merge(ConfigEntityType entity, Map<UUID, T> published, Class<T> type) {
    Map<UUID, BiConfigChange> pending = new HashMap<>();
    for (BiConfigChange change : changes.findAllByEntityType(entity)) {
      pending.put(change.getTargetId(), change);
    }
    List<Item<T>> out = new ArrayList<>();
    for (Map.Entry<UUID, T> row : published.entrySet()) {
      BiConfigChange change = pending.remove(row.getKey());
      out.add(new Item<>(row.getKey(), row.getValue(), effective(row.getValue(), change, type), change));
    }
    pending.values().stream()
        .filter(c -> c.getOp() == ConfigChangeOp.CREATE)
        .sorted(Comparator.comparing(BiConfigChange::getCreatedAt))
        .map(c -> new Item<>(c.getTargetId(), null, convert(read(c.getPayload()), type), c))
        .forEach(out::add);
    return out;
  }

  private <T> T effective(T published, BiConfigChange change, Class<T> type) {
    if (change == null || change.getOp() == ConfigChangeOp.DELETE || change.getPayload() == null) {
      return published;
    }
    return convert(read(change.getPayload()), type);
  }

  private static boolean preferred(Item<AdminTemplateState> a, Item<AdminTemplateState> b) {
    Function<Item<AdminTemplateState>, Boolean> live =
        i -> i.change() != null || (i.published() != null && i.published().active());
    if (!live.apply(a).equals(live.apply(b))) {
      return live.apply(a);
    }
    int av = a.published() == null ? Integer.MAX_VALUE : a.published().templateVersion();
    int bv = b.published() == null ? Integer.MAX_VALUE : b.published().templateVersion();
    return av > bv;
  }
}
