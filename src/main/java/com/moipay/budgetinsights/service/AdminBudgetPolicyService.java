package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminBudgetPolicyInput;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyView;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import java.util.UUID;

/**
 * Admin: the budget policy (Configuration › Budget Policy). Edits are drafts in the shared
 * changeset and go live with the next publish, like every other configuration change.
 */
public interface AdminBudgetPolicyService {

  AdminBudgetPolicyView get();

  /** Saves the whole policy as a draft; saving the published values back drops the draft. */
  AdminWriteResult<AdminBudgetPolicyView> update(AdminBudgetPolicyInput input, UUID userId, Long ifMatch);

  /** Drops the pending policy change, if any. */
  AdminWriteResult<AdminBudgetPolicyView> discard(Long ifMatch);
}
