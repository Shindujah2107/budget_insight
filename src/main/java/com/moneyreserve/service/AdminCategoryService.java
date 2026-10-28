package com.moneyreserve.service;

import com.moneyreserve.dto.AdminCategoryInput;
import com.moneyreserve.dto.AdminCategoryState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminWriteResult;
import java.util.UUID;

/**
 * Admin: spending categories. Every write is a draft in the shared changeset.
 *
 * <p>{@code OTHER} is the categoriser's fallback and is locked. A published code never changes. A
 * category in use by a transaction, merchant rule or budget limit cannot be deleted, only
 * deactivated.
 */
public interface AdminCategoryService {

  /** Usage shown beside a category. */
  record Usage(long transactions30d, long transactionsTotal, long merchantRules, long budgetLimits) {}

  /** Usage that decides whether a delete is possible. */
  record DeleteCheck(long transactionsTotal, long merchantRules, long budgetLimits, boolean deletable) {}

  AdminConfigList<AdminConfigItem<AdminCategoryState>> list(String q, String active);

  AdminWriteResult<AdminConfigItem<AdminCategoryState>> create(
      AdminCategoryInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminCategoryState>> update(
      UUID id, AdminCategoryInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminCategoryState>> deactivate(
      UUID id, UUID moveRulesTo, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminCategoryState>> activate(UUID id, UUID userId, Long ifMatch);

  DeleteCheck usage(UUID id);

  AdminWriteResult<AdminConfigItem<AdminCategoryState>> delete(UUID id, UUID userId, Long ifMatch);
}
