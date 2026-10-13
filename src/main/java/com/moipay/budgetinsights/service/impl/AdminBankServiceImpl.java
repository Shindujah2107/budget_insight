package com.moipay.budgetinsights.service.impl;

import com.moipay.budgetinsights.domain.BiBank;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.domain.ConfigItemState;
import com.moipay.budgetinsights.dto.AdminBankInput;
import com.moipay.budgetinsights.dto.AdminBankItem;
import com.moipay.budgetinsights.dto.AdminBankState;
import com.moipay.budgetinsights.dto.AdminBankUpdate;
import com.moipay.budgetinsights.dto.AdminSenderState;
import com.moipay.budgetinsights.dto.AdminTemplateState;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiAccountRepository;
import com.moipay.budgetinsights.repository.BiBankRepository;
import com.moipay.budgetinsights.service.AdminBankService;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank registry behind {@code /admin/config/banks}.
 *
 * <p>Banks added through "Add bank SMS sender" ({@link #createPending}) stay pending until that
 * sender is published, and disappear again if the draft is discarded.
 *
 * <p>Adding a bank applies immediately (it reaches nothing until a sender for it is published).
 * Renaming, turning on/off and deleting a live bank are drafts (V1.10.0): they show in Review &amp;
 * publish and in release history, and can be restored. The guard rails are about not orphaning
 * what already points at a bank:
 *
 * <ul>
 *   <li>the code never changes after creation (senders, templates and accounts carry it as text);
 *   <li>a bank cannot be turned off while an active sender or template — drafts included — reads
 *       its SMS;
 *   <li>a bank can be deleted only if nothing ever referred to it (a typo fix); otherwise turn it
 *       off.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBankServiceImpl implements AdminBankService {

  private final BiBankRepository banks;
  private final BiAccountRepository accounts;
  private final AdminEffectiveView view;
  private final AdminDrafts drafts;

  @Override
  @Transactional(readOnly = true)
  public List<AdminBankItem> list(String active) {
    Usages usages = usages();
    return banks.findAll().stream()
        .map(b -> toItem(b, usages))
        .filter(i -> matches(i.active(), active))
        .sorted(Comparator.comparing(AdminBankItem::bankName, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @Override
  @Transactional
  public AdminBankItem create(AdminBankInput input, UUID staffId) {
    BiBank bank = insert(input, staffId, false);
    log.info("bi-admin bank created code={} by={}", bank.getBankCode(), staffId);
    return toItem(bank, usages());
  }

  @Override
  @Transactional
  public String createPending(AdminBankInput input, UUID staffId) {
    BiBank bank = insert(input, staffId, true);
    log.info("bi-admin bank created with sender draft code={} by={}", bank.getBankCode(), staffId);
    return bank.getBankCode();
  }

  @Override
  @Transactional
  public int confirmPublished() {
    List<BiBank> pending = banks.findAllByIsPendingTrue();
    if (pending.isEmpty()) {
      return 0;
    }
    Usages u = usages();
    int n = 0;
    for (BiBank b : pending) {
      String code = b.getBankCode();
      boolean live =
          u.senders().stream().anyMatch(i -> i.published() != null && code.equals(i.published().bankCode()))
              || u.templates().stream()
                  .anyMatch(i -> i.published() != null && code.equals(i.published().bankCode()));
      if (live) {
        b.setIsPending(false);
        b.setUpdatedAt(OffsetDateTime.now());
        banks.save(b);
        n++;
      }
    }
    return n;
  }

  @Override
  @Transactional
  public int purgeOrphanPending() {
    List<BiBank> pending = banks.findAllByIsPendingTrue();
    if (pending.isEmpty()) {
      return 0;
    }
    Usages u = usages();
    int n = 0;
    for (BiBank b : pending) {
      if (usage(b.getBankCode(), u).deletable()) {
        banks.delete(b);
        n++;
        log.info("bi-admin pending bank removed with its discarded draft code={}", b.getBankCode());
      }
    }
    return n;
  }

  private BiBank insert(AdminBankInput input, UUID staffId, boolean pending) {
    String code = input.bankCode().trim();
    String name = name(input.bankName());
    if (banks.existsById(code)) {
      BiBank other = banks.findById(code).orElseThrow();
      throw duplicate("bankCode", "bank code " + code + " already exists (" + other.getBankName() + ")");
    }
    banks.findFirstByBankNameIgnoreCase(name)
        .ifPresent(b -> {
          throw duplicate("bankName", "already used by " + b.getBankCode() + " (" + b.getBankName() + ")");
        });
    // A pending rename to this name would collide at publish.
    requireNameFree(name, null);
    BiBank bank =
        BiBank.builder()
            .bankCode(code)
            .bankName(name)
            .isActive(true)
            .isPending(pending)
            .createdAt(OffsetDateTime.now())
            .createdBy(staffId)
            .build();
    try {
      banks.saveAndFlush(bank);
    } catch (DataIntegrityViolationException race) {
      throw duplicate("bankCode", "bank code or name already exists");
    }
    return bank;
  }

  /**
   * Rename a bank or turn it on / off. A bank that is live is changed through a draft (Review &amp;
   * publish, history, restore); a bank still pending with its first sender draft is not live yet
   * and is changed directly.
   */
  @Override
  @Transactional
  public AdminBankItem update(String bankCode, AdminBankUpdate input, UUID staffId, Long ifMatch) {
    drafts.begin(ifMatch);
    BiBank bank = find(bankCode);
    if (input == null || (input.bankName() == null && input.active() == null)) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Some fields are not valid.",
          AdminFieldError.of("bankName", "send bankName and/or active"));
    }
    Item<AdminBankState> item = item(bank.getBankCode());
    if (!item.survives()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID,
          "This bank is waiting to be deleted. Discard that change first.");
    }
    AdminBankState current = item.effective();
    String name = input.bankName() != null ? name(input.bankName()) : current.bankName();
    if (input.bankName() != null) {
      requireNameFree(name, item.id());
    }
    boolean active = input.active() != null ? input.active() : current.active();
    if (!active && current.active()) {
      AdminBankItem.Usage usage = usage(bank.getBankCode(), usages());
      if (!usage.deactivatable()) {
        throw inUse(bank.getBankCode(), usage,
            "Turn off or delete this bank's active SMS senders and templates first.");
      }
    }
    AdminBankState desired = new AdminBankState(bank.getBankCode(), name, active);

    if (Boolean.TRUE.equals(bank.getIsPending())) {
      bank.setBankName(name);
      bank.setIsActive(active);
      bank.setUpdatedAt(OffsetDateTime.now());
      bank.setUpdatedBy(staffId);
      try {
        banks.saveAndFlush(bank);
      } catch (DataIntegrityViolationException race) {
        throw duplicate("bankName", "bank name already exists");
      }
    } else {
      AdminBankState published = item.published();
      ConfigChangeOp op =
          published.active() != active && published.bankName().equals(name)
              ? (active ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE)
              : ConfigChangeOp.UPDATE;
      drafts.draft(ConfigEntityType.BANK, item.id(), op, published, desired, staffId);
    }
    long revision = drafts.commit();
    log.info("bi-admin bank edit saved code={} active={} pending={} by={}",
        bank.getBankCode(), active, bank.getIsPending(), staffId);
    return withRevision(toItem(banks.findById(bank.getBankCode()).orElse(bank), usages()), revision);
  }

  /**
   * Delete a bank nothing ever used (a typo fix). A live bank is deleted through a draft; a pending
   * one directly.
   */
  @Override
  @Transactional
  public long delete(String bankCode, UUID staffId, Long ifMatch) {
    drafts.begin(ifMatch);
    BiBank bank = find(bankCode);
    AdminBankItem.Usage usage = usage(bank.getBankCode(), usages());
    if (!usage.deletable()) {
      throw inUse(bank.getBankCode(), usage,
          "Senders, templates or customer accounts refer to this bank. Turn it off instead.");
    }
    if (Boolean.TRUE.equals(bank.getIsPending())) {
      banks.delete(bank);
    } else {
      Item<AdminBankState> item = item(bank.getBankCode());
      drafts.draft(ConfigEntityType.BANK, item.id(), ConfigChangeOp.DELETE, item.published(), null, staffId);
    }
    long revision = drafts.commit();
    log.info("bi-admin bank delete saved code={} pending={} by={}", bank.getBankCode(), bank.getIsPending(), staffId);
    return revision;
  }

  /**
   * A bank name must be unique after publish: no other bank may hold it once pending edits apply.
   * A bank waiting to be deleted still holds its name (the delete applies after renames), and
   * publish frees renamed banks' old names before applying the new ones, so swaps are fine.
   */
  private void requireNameFree(String name, UUID self) {
    view.banks().stream()
        .filter(i -> !i.id().equals(self))
        .filter(i -> i.effective().bankName().equalsIgnoreCase(name))
        .findFirst()
        .ifPresent(other -> {
          throw duplicate("bankName", "already used by bank " + other.effective().bankCode()
              + (other.change() == null ? "" : " (pending change)"));
        });
  }

  private Item<AdminBankState> item(String code) {
    UUID id = AdminEffectiveView.bankId(code);
    return view.banks().stream()
        .filter(i -> i.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new BudgetInsightsException(
            ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That bank does not exist."));
  }

  private static AdminBankItem withRevision(AdminBankItem i, long revision) {
    return new AdminBankItem(
        i.bankCode(), i.bankName(), i.pending(), i.active(), i.usage(), i.createdAt(), i.updatedAt(),
        i.state(), i.pendingChangeId(), i.published(), revision);
  }

  // ------------------------------------------------------------------------------------- helpers

  private BiBank find(String bankCode) {
    String code = bankCode == null ? "" : bankCode.trim();
    return banks.findById(code)
        .orElseThrow(() -> new BudgetInsightsException(
            ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That bank does not exist."));
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

  private static String name(String raw) {
    return raw.trim().replaceAll("\\s+", " ");
  }

  private static BudgetInsightsException duplicate(String field, String message) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_CONFIG_DUPLICATE, "A bank with that " + field + " already exists.",
        AdminFieldError.of(field, message));
  }

  private static BudgetInsightsException inUse(String code, AdminBankItem.Usage usage, String message) {
    return BudgetInsightsException.withDetails(
        ErrorCode.ADMIN_BANK_IN_USE, message, Map.of("bankCode", code, "usage", usage));
  }

  /** Everything that names a bank, read once per request: senders and templates incl. drafts. */
  private Usages usages() {
    return new Usages(
        view.senders(), view.templates(), view.currentTemplates(), view.banks());
  }

  private record Usages(
      List<AdminEffectiveView.Item<AdminSenderState>> senders,
      List<AdminEffectiveView.Item<AdminTemplateState>> templates,
      List<AdminEffectiveView.Item<AdminTemplateState>> current,
      List<AdminEffectiveView.Item<AdminBankState>> banks) {}

  private AdminBankItem.Usage usage(String code, Usages u) {
    int senders = (int) u.senders().stream().filter(i -> code.equals(bankOf(i))).count();
    int activeSenders =
        (int) u.senders().stream()
            .filter(i -> i.survives() && code.equals(i.effective().bankCode()) && i.effective().active())
            .count();
    int templates = (int) u.templates().stream().filter(i -> code.equals(bankOf(i))).count();
    int activeTemplates =
        (int) u.current().stream()
            .filter(i -> i.survives() && code.equals(i.effective().bankCode()) && i.effective().active())
            .count();
    long customerAccounts = accounts.countByBankCode(code);
    return new AdminBankItem.Usage(
        senders,
        activeSenders,
        templates,
        activeTemplates,
        customerAccounts,
        senders == 0 && templates == 0 && customerAccounts == 0,
        activeSenders == 0 && activeTemplates == 0);
  }

  private static String bankOf(AdminEffectiveView.Item<?> item) {
    Object state = item.effective() != null ? item.effective() : item.published();
    if (state instanceof AdminSenderState s) {
      return s.bankCode();
    }
    if (state instanceof AdminTemplateState t) {
      return t.bankCode();
    }
    return null;
  }

  /** The bank with any pending edit applied (bankName, active) and its live values beside. */
  private AdminBankItem toItem(BiBank b, Usages u) {
    UUID id = AdminEffectiveView.bankId(b.getBankCode());
    Item<AdminBankState> item =
        u.banks().stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    AdminBankState published = view.toState(b);
    AdminBankState effective = item == null ? published : item.effective();
    return new AdminBankItem(
        b.getBankCode(),
        effective.bankName(),
        Boolean.TRUE.equals(b.getIsPending()),
        effective.active(),
        usage(b.getBankCode(), u),
        b.getCreatedAt(),
        b.getUpdatedAt(),
        item == null ? ConfigItemState.PUBLISHED : item.state(),
        item == null ? null : item.changeId(),
        published,
        null);
  }
}
