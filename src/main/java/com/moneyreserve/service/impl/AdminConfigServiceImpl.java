package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBank;
import com.moneyreserve.dto.AdminBank;
import com.moneyreserve.dto.AdminBankState;
import com.moneyreserve.dto.AdminConfigSummary;
import com.moneyreserve.dto.AdminSenderState;
import com.moneyreserve.dto.AdminTemplateState;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiBankRepository;
import com.moneyreserve.repository.BiConfigChangeRepository;
import com.moneyreserve.service.AdminConfigService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminConfigServiceImpl implements AdminConfigService {

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminReleaseVersion releases;
  private final BiConfigChangeRepository changes;
  private final InsightsProperties properties;
  private final BiBankRepository bankRegistry;

  @Override
  @Transactional(readOnly = true)
  public AdminConfigSummary summary() {
    List<AdminEffectiveView.Item<AdminSenderState>> senders =
        view.senders().stream().filter(AdminEffectiveView.Item::survives).toList();
    List<AdminEffectiveView.Item<AdminTemplateState>> templates =
        view.currentTemplates().stream()
            .filter(t -> t.survives() && t.effective().active())
            .toList();
    return new AdminConfigSummary(
        (int) view.categories().stream().filter(AdminEffectiveView.Item::survives).count(),
        (int) view.merchantRules().stream().filter(AdminEffectiveView.Item::survives).count(),
        senders.size(),
        senders.stream().map(s -> s.effective().bankCode()).collect(Collectors.toSet()).size(),
        templates.size(),
        templates.stream().map(t -> t.effective().bankCode()).collect(Collectors.toSet()).size(),
        releases.current(),
        (int) changes.count(),
        drafts.revision());
  }

  /**
   * Active banks from the registry ({@code bi_bank}) with pending edits applied, by name — what the
   * dropdowns offer.
   */
  @Override
  @Transactional(readOnly = true)
  public List<AdminBank> banks() {
    return view.banks().stream()
        .filter(b -> b.survives() && b.effective().active())
        .map(b -> new AdminBank(b.effective().bankCode(), b.effective().bankName()))
        .sorted((a, b) -> a.bankName().compareToIgnoreCase(b.bankName()))
        .toList();
  }

  @Override
  public List<String> iconKeys() {
    return List.copyOf(properties.getAdmin().getIconKeys());
  }

  @Override
  @Transactional(readOnly = true)
  public String bankName(String bankCode) {
    return bankCode == null
        ? null
        : bankRegistry.findById(bankCode).map(BiBank::getBankName).orElse(bankCode);
  }

  /**
   * A sender or template may only name a bank that is in the registry and active — after pending
   * bank edits. A bank that was turned off keeps its name everywhere it is shown, but nothing new
   * can be added for it.
   */
  @Override
  @Transactional(readOnly = true)
  public void requireBank(String bankCode) {
    AdminBankState bank =
        bankCode == null
            ? null
            : view.banks().stream()
                .filter(b -> b.survives() && bankCode.equals(b.effective().bankCode()))
                .map(AdminEffectiveView.Item::effective)
                .findFirst()
                .orElse(null);
    if (bank == null) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Bank " + bankCode + " is not in the bank list. Add it when adding its SMS sender.",
          AdminFieldError.of("bankCode", "not a configured bank"));
    }
    if (!bank.active()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Bank " + bankCode + " is turned off.",
          AdminFieldError.of("bankCode", "bank is inactive"));
    }
  }
}
