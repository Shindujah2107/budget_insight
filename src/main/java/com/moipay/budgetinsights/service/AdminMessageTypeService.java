package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminMessageTypeInput;
import com.moipay.budgetinsights.dto.AdminMessageTypeItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admin: the message type list ({@code bi_message_type}) SMS templates pick from. Adding and editing
 * apply immediately; a type is never deleted, only turned off (templates and transactions carry
 * the code as text).
 */
public interface AdminMessageTypeService {

  /** The groups a message type may belong to, in display order. */
  List<String> LOGICAL_TYPES =
      List.of(
          "ACCOUNT_TRANSACTION",
          "CARD_PURCHASE",
          "ATM_WITHDRAWAL",
          "CASH_DEPOSIT",
          "TRANSFER",
          "PAYMENT",
          "CHEQUE",
          "LOAN",
          "BANK_CHARGE_OR_REFUND",
          "REFUND_OR_REVERSAL");

  /** @param active {@code true} (default), {@code false} or {@code all} */
  List<AdminMessageTypeItem> list(String active);

  AdminMessageTypeItem create(AdminMessageTypeInput input, UUID staffId);

  AdminMessageTypeItem update(String code, AdminMessageTypeInput input, UUID staffId);

  /**
   * The group a new template of this type takes, when the type is on the list. Empty when it is
   * not, or when the list is empty (not set up), so callers keep what they were given.
   */
  Optional<String> logicalTypeFor(String code);

  /**
   * Refuses a message type a new template may not use: not on the list, or turned off. Does
   * nothing while the list is empty (not set up yet), so nothing is refused before it exists.
   */
  void requireUsableForNewTemplate(String code);
}
