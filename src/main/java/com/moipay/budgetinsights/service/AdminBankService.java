package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminBankInput;
import com.moipay.budgetinsights.dto.AdminBankItem;
import com.moipay.budgetinsights.dto.AdminBankUpdate;
import java.util.List;
import java.util.UUID;

/**
 * Admin: the bank registry ({@code bi_bank}). Adding a bank is immediate; renaming, turning on/off
 * and deleting a live bank are drafts published with the rest of the configuration.
 */
public interface AdminBankService {

  /** @param active {@code true} (default), {@code false} or {@code all} */
  List<AdminBankItem> list(String active);

  AdminBankItem create(AdminBankInput input, UUID staffId);

  /**
   * Creates a bank together with a sender draft, in the caller's transaction. It is marked pending
   * until {@link #confirmPublished()} sees a published sender or template for it.
   *
   * @return the bank code
   */
  String createPending(AdminBankInput input, UUID staffId);

  /** After a publish: pending banks that now have a published sender or template stop being pending. */
  int confirmPublished();

  /** After drafts are discarded: pending banks that nothing refers to any more are removed. */
  int purgeOrphanPending();

  /** Rename / turn on or off; a draft for a live bank. The result carries the new revision. */
  AdminBankItem update(String bankCode, AdminBankUpdate input, UUID staffId, Long ifMatch);

  /** Delete an unused bank; a draft for a live bank. @return the new changeset revision */
  long delete(String bankCode, UUID staffId, Long ifMatch);
}
