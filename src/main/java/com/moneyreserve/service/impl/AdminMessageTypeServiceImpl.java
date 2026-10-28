package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiMessageType;
import com.moneyreserve.dto.AdminMessageTypeInput;
import com.moneyreserve.dto.AdminMessageTypeItem;
import com.moneyreserve.dto.AdminTemplateState;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiMessageTypeRepository;
import com.moneyreserve.service.AdminMessageTypeService;
import com.moneyreserve.service.impl.AdminEffectiveView.Item;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The message type list behind {@code /admin/config/message-types}.
 *
 * <p>Guard rails:
 *
 * <ul>
 *   <li>the code never changes and a type is never deleted: templates, handsets and transactions
 *       carry it as text, so it is turned off instead (existing templates keep reading SMS; only new
 *       templates can no longer pick it);
 *   <li>the group comes from a fixed list ({@link #LOGICAL_TYPES});
 *   <li>a fixed category must be an existing, active category.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminMessageTypeServiceImpl implements AdminMessageTypeService {

  private final BiMessageTypeRepository messageTypes;
  private final BiCategoryRepository categories;
  private final AdminEffectiveView view;
  private final MessageTypeCategorySync categorySync;

  @Override
  @Transactional(readOnly = true)
  public List<AdminMessageTypeItem> list(String active) {
    Map<String, int[]> usage = usage();
    return messageTypes.findAll().stream()
        .map(t -> toItem(t, usage))
        .filter(i -> matches(i.active(), active))
        .sorted(Comparator.comparing(AdminMessageTypeItem::code))
        .toList();
  }

  @Override
  @Transactional
  public AdminMessageTypeItem create(AdminMessageTypeInput input, UUID staffId) {
    String code = input.code() == null ? "" : input.code().trim();
    if (code.isEmpty()) {
      throw invalid("code", "code is required");
    }
    if (messageTypes.existsById(code)) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "The message type " + code + " already exists.",
          AdminFieldError.of("code", "already on the list"));
    }
    BiMessageType row =
        BiMessageType.builder()
            .code(code)
            .displayName(name(input.displayName()))
            .logicalType(logicalType(input.logicalType()))
            .defaultDirection(orDefault(input.defaultDirection(), "FROM_SMS"))
            .defaultInstrument(orDefault(input.defaultInstrument(), "BANK"))
            .fixedCategoryCode(fixedCategory(input.fixedCategoryCode()))
            .isActive(input.active() == null || input.active())
            .createdAt(OffsetDateTime.now())
            .createdBy(staffId)
            .build();
    try {
      messageTypes.saveAndFlush(row);
    } catch (DataIntegrityViolationException race) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "The message type " + code + " already exists.",
          AdminFieldError.of("code", "already on the list"));
    }
    refreshCategoriesAfterCommit();
    log.info(
        "bi-admin message type created code={} logicalType={} fixedCategory={} by={}",
        code, row.getLogicalType(), row.getFixedCategoryCode(), staffId);
    return toItem(row, usage());
  }

  @Override
  @Transactional
  public AdminMessageTypeItem update(String code, AdminMessageTypeInput input, UUID staffId) {
    BiMessageType row = find(code);
    row.setDisplayName(name(input.displayName()));
    row.setLogicalType(logicalType(input.logicalType()));
    row.setDefaultDirection(orDefault(input.defaultDirection(), row.getDefaultDirection()));
    row.setDefaultInstrument(orDefault(input.defaultInstrument(), row.getDefaultInstrument()));
    row.setFixedCategoryCode(fixedCategory(input.fixedCategoryCode()));
    if (input.active() != null) {
      row.setIsActive(input.active());
    }
    row.setUpdatedAt(OffsetDateTime.now());
    row.setUpdatedBy(staffId);
    messageTypes.saveAndFlush(row);
    refreshCategoriesAfterCommit();
    log.info(
        "bi-admin message type updated code={} logicalType={} fixedCategory={} active={} by={}",
        row.getCode(), row.getLogicalType(), row.getFixedCategoryCode(), row.getIsActive(), staffId);
    return toItem(row, usage());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> logicalTypeFor(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    return messageTypes.findById(code.trim()).map(BiMessageType::getLogicalType);
  }

  @Override
  @Transactional(readOnly = true)
  public void requireUsableForNewTemplate(String code) {
    if (messageTypes.count() == 0) {
      return;
    }
    String key = code == null ? "" : code.trim();
    BiMessageType row =
        messageTypes.findById(key)
            .orElseThrow(() -> invalid(
                "messageType",
                key + " is not on the message type list. Pick one from the list, or ask a Super Admin to add it."));
    if (!Boolean.TRUE.equals(row.getIsActive())) {
      throw invalid("messageType", key + " is turned off on the message type list. Pick another one.");
    }
  }

  // ------------------------------------------------------------------------------------- helpers

  /** After the change is committed, so another reader never sees a category the rollback undid. */
  private void refreshCategoriesAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              categorySync.refresh();
            }
          });
    } else {
      categorySync.refresh();
    }
  }

  private BiMessageType find(String code) {
    String key = code == null ? "" : code.trim();
    return messageTypes.findById(key)
        .orElseThrow(() -> new BudgetInsightsException(
            ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That message type does not exist."));
  }

  private static String name(String raw) {
    String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    if (name.length() < 2 || name.length() > 64) {
      throw invalid("displayName", "displayName must be 2-64 characters");
    }
    return name;
  }

  private static String logicalType(String raw) {
    String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!LOGICAL_TYPES.contains(value)) {
      throw invalid("logicalType", "must be one of " + String.join(", ", LOGICAL_TYPES));
    }
    return value;
  }

  private static String orDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String fixedCategory(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String code = raw.trim().toUpperCase(Locale.ROOT);
    Optional<BiCategory> category = categories.findByCodeAndIsActiveTrue(code);
    if (category.isEmpty()) {
      throw invalid("fixedCategoryCode", code + " is not an active category");
    }
    return code;
  }

  private static BudgetInsightsException invalid(String field, String message) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_FIELD_INVALID, "Some fields are not valid.", AdminFieldError.of(field, message));
  }

  private static boolean matches(boolean isActive, String active) {
    if (active == null || active.isBlank() || active.equalsIgnoreCase("true")) {
      return isActive;
    }
    if (active.equalsIgnoreCase("false")) {
      return !isActive;
    }
    return true;
  }

  /** code → {templates, active templates}: one template per bank and type, drafts included. */
  private Map<String, int[]> usage() {
    Map<String, int[]> out = new HashMap<>();
    List<Item<AdminTemplateState>> current = new ArrayList<>(view.currentTemplates());
    for (Item<AdminTemplateState> item : current) {
      AdminTemplateState base = item.effective() != null ? item.effective() : item.published();
      if (base == null || base.messageType() == null) {
        continue;
      }
      int[] counts = out.computeIfAbsent(base.messageType(), k -> new int[2]);
      counts[0]++;
      if (item.survives() && item.effective() != null && item.effective().active()) {
        counts[1]++;
      }
    }
    return out;
  }

  private static AdminMessageTypeItem toItem(BiMessageType t, Map<String, int[]> usage) {
    int[] counts = usage.getOrDefault(t.getCode(), new int[2]);
    return new AdminMessageTypeItem(
        t.getCode(),
        t.getDisplayName(),
        t.getLogicalType(),
        t.getDefaultDirection(),
        t.getDefaultInstrument(),
        t.getFixedCategoryCode(),
        Boolean.TRUE.equals(t.getIsActive()),
        counts[0],
        counts[1],
        t.getCreatedBy() == null,
        t.getCreatedAt(),
        t.getUpdatedAt());
  }
}
