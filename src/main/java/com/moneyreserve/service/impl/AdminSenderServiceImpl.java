package com.moneyreserve.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.moneyreserve.domain.ConfigChangeOp;
import com.moneyreserve.domain.ConfigEntityType;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminSenderInput;
import com.moneyreserve.dto.AdminSenderState;
import com.moneyreserve.dto.AdminTemplateState;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.service.AdminBankService;
import com.moneyreserve.service.AdminConfigService;
import com.moneyreserve.service.AdminSenderService;
import com.moneyreserve.service.impl.AdminEffectiveView.Item;
import com.moneyreserve.service.impl.AdminUsageCounts.Counts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSenderServiceImpl implements AdminSenderService {

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminUsageCounts usage;
  private final AdminReleaseVersion releases;
  private final AdminConfigService config;
  private final AdminBankService bankRegistry;

  @Override
  @Transactional(readOnly = true)
  public AdminConfigList<AdminConfigItem<AdminSenderState>> list(
      String q, String active, String bank) {
    List<Item<AdminTemplateState>> templates = view.currentTemplates();
    Map<UUID, Counts> parsed = usage.transactionsByTemplate();
    List<AdminConfigItem<AdminSenderState>> items =
        view.senders().stream()
            .filter(i -> AdminListFilters.matchesActive(active, i.effective().active()))
            .filter(i -> bank == null || bank.isBlank() || bank.equals(i.effective().bankCode()))
            .filter(
                i ->
                    AdminListFilters.matchesText(
                        q,
                        i.effective().senderId(),
                        i.effective().bankCode(),
                        config.bankName(i.effective().bankCode())))
            .map(i -> toItem(i, templates, parsed))
            .toList();
    return new AdminConfigList<>(items, drafts.revision(), releases.current());
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminSenderState>> create(
      AdminSenderInput in, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    String bankCode = bankCodeFor(in);
    AdminSenderState desired = toState(in, bankCode);
    if (in.newBank() == null) {
      config.requireBank(bankCode);
    }
    requireUnique(desired, null);
    if (in.newBank() != null) {
      // Same transaction as the draft: a failure below rolls the new bank back too.
      bankRegistry.createPending(in.newBank(), userId);
    }
    UUID id = UUID.randomUUID();
    drafts.draft(ConfigEntityType.SENDER, id, ConfigChangeOp.CREATE, null, desired, userId);
    log.info(
        "bi-admin sender create drafted id={} senderId={} bank={} newBank={} active={} by={}",
        id, desired.senderId(), desired.bankCode(), in.newBank() != null, desired.active(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminSenderState>> update(
      UUID id, AdminSenderInput in, boolean acknowledged, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminSenderState> item = require(id);
    requireNotDeleting(item);
    if (in.newBank() != null) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Some fields are not valid.",
          AdminFieldError.of("newBank", "only when adding a sender; choose an existing bankCode"));
    }
    AdminSenderState desired = toState(in, bankCodeFor(in));
    config.requireBank(desired.bankCode());
    requireUnique(desired, id);
    boolean leaving =
        item.effective().active()
            && (!desired.active()
                || !desired.senderId().equals(item.effective().senderId())
                || !desired.bankCode().equals(item.effective().bankCode()));
    if (leaving) {
      // An edit that takes a sender out of use needs the same acknowledgement as turning it off
      // (?acknowledgeTemplateImpact=true, as on S5).
      requireAcknowledged(item.effective(), acknowledged);
    }
    ConfigChangeOp op = item.published() == null ? ConfigChangeOp.CREATE : ConfigChangeOp.UPDATE;
    drafts.draft(ConfigEntityType.SENDER, id, op, item.published(), desired, userId);
    log.info(
        "bi-admin sender {} drafted id={} senderId={} bank={} active={} leavingUse={} by={}",
        op, id, desired.senderId(), desired.bankCode(), desired.active(), leaving, userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminSenderState>> deactivate(
      UUID id, boolean acknowledged, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminSenderState> item = require(id);
    requireNotDeleting(item);
    requireAcknowledged(item.effective(), acknowledged);
    ConfigChangeOp op =
        item.published() == null ? ConfigChangeOp.CREATE : ConfigChangeOp.DEACTIVATE;
    drafts.draft(
        ConfigEntityType.SENDER, id, op, item.published(), item.effective().withActive(false), userId);
    log.info(
        "bi-admin sender deactivate drafted id={} senderId={} bank={} by={}",
        id, item.effective().senderId(), item.effective().bankCode(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminSenderState>> delete(
      UUID id, boolean acknowledged, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminSenderState> item = require(id);
    // Templates that list the sender are affected whether or not it is already (draft-)inactive.
    requireAcknowledged(item.effective(), acknowledged);
    drafts.draft(ConfigEntityType.SENDER, id, ConfigChangeOp.DELETE, item.published(), null, userId);
    log.info(
        "bi-admin sender delete drafted id={} senderId={} bank={} by={}",
        id, item.effective().senderId(), item.effective().bankCode(), userId);
    return result(id);
  }

  /** True when a template's {@code fieldMap.senders} names the sender (case-sensitive). */
  static boolean lists(AdminTemplateState template, String senderId) {
    JsonNode senders = template.fieldMap() == null ? null : template.fieldMap().path("senders");
    if (senders == null || !senders.isArray()) {
      return false;
    }
    for (JsonNode s : senders) {
      if (senderId.equals(s.asText())) {
        return true;
      }
    }
    return false;
  }

  private void requireAcknowledged(AdminSenderState sender, boolean acknowledged) {
    List<Map<String, Object>> affected =
        view.currentTemplates().stream()
            .filter(
                t ->
                    t.survives()
                        && t.effective().active()
                        && t.effective().bankCode().equals(sender.bankCode())
                        && lists(t.effective(), sender.senderId()))
            .map(
                t ->
                    Map.<String, Object>of(
                        "templateId", t.id(),
                        "bankCode", t.effective().bankCode(),
                        "messageType", t.effective().messageType()))
            .toList();
    if (!affected.isEmpty() && !acknowledged) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_SENDER_IN_USE,
          affected.size()
              + " active SMS template(s) read messages from "
              + sender.senderId()
              + ". Confirm to continue.",
          Map.of("templates", affected));
    }
  }

  private AdminWriteResult<AdminConfigItem<AdminSenderState>> result(UUID id) {
    long revision = drafts.commit();
    Optional<Item<AdminSenderState>> item =
        view.senders().stream().filter(i -> i.id().equals(id)).findFirst();
    return new AdminWriteResult<>(
        item.map(i -> toItem(i, view.currentTemplates(), usage.transactionsByTemplate()))
            .orElse(null),
        revision);
  }

  private AdminConfigItem<AdminSenderState> toItem(
      Item<AdminSenderState> i, List<Item<AdminTemplateState>> templates, Map<UUID, Counts> parsed) {
    long activeTemplates = 0;
    long recent = 0;
    for (Item<AdminTemplateState> t : templates) {
      AdminTemplateState s = t.effective();
      if (s.bankCode().equals(i.effective().bankCode()) && lists(s, i.effective().senderId())) {
        if (t.survives() && s.active()) {
          activeTemplates++;
        }
        recent += parsed.getOrDefault(t.id(), Counts.ZERO).recent();
      }
    }
    return new AdminConfigItem<>(
        i.id(),
        i.state(),
        i.changeId(),
        i.published(),
        i.effective(),
        false,
        new Usage(activeTemplates, recent));
  }

  private Item<AdminSenderState> require(UUID id) {
    return view.senders().stream()
        .filter(i -> i.id().equals(id))
        .findFirst()
        .orElseThrow(
            () ->
                new BudgetInsightsException(
                    ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That sender does not exist."));
  }

  private static void requireNotDeleting(Item<AdminSenderState> item) {
    if (!item.survives()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID,
          "This sender is waiting to be deleted. Discard that change first.");
    }
  }

  private void requireUnique(AdminSenderState desired, UUID self) {
    boolean clash =
        view.senders().stream()
            .anyMatch(
                i ->
                    !i.id().equals(self)
                        && i.effective().bankCode().equals(desired.bankCode())
                        && i.effective().senderId().equals(desired.senderId()));
    if (clash) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          desired.senderId() + " is already a sender for this bank.",
          AdminFieldError.of("senderId", "already used for this bank, including pending drafts"));
    }
  }

  /** Exactly one of {@code bankCode} (an existing bank) and {@code newBank}. */
  private static String bankCodeFor(AdminSenderInput in) {
    boolean hasCode = in.bankCode() != null && !in.bankCode().isBlank();
    if (hasCode == (in.newBank() != null)) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Some fields are not valid.",
          AdminFieldError.of(
              "bankCode", hasCode ? "send bankCode or newBank, not both" : "choose a bank (bankCode) or add one (newBank)"));
    }
    if (hasCode) {
      return in.bankCode().trim();
    }
    return in.newBank().bankCode().trim();
  }

  private static AdminSenderState toState(AdminSenderInput in, String bankCode) {
    String country =
        in.countryCode() == null || in.countryCode().isBlank() ? "LK" : in.countryCode();
    return new AdminSenderState(bankCode, in.senderId().trim(), country, in.active());
  }
}
