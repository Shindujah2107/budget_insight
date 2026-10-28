package com.moneyreserve.service;

import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminSenderInput;
import com.moneyreserve.dto.AdminSenderState;
import com.moneyreserve.dto.AdminWriteResult;
import java.util.UUID;

/**
 * Admin: bank SMS senders, the allowlist a handset checks before it reads a message at all.
 *
 * <p>Turning off or deleting a sender that an active template lists in {@code fieldMap.senders}
 * needs an explicit acknowledgement.
 */
public interface AdminSenderService {

  /** Usage of one sender. */
  record Usage(long activeTemplates, long transactions30d) {}

  AdminConfigList<AdminConfigItem<AdminSenderState>> list(String q, String active, String bank);

  AdminWriteResult<AdminConfigItem<AdminSenderState>> create(
      AdminSenderInput input, UUID userId, Long ifMatch);

  /**
   * @param acknowledged required when the edit turns off, renames or moves a sender that active
   *     templates read from (otherwise 409 E.016)
   */
  AdminWriteResult<AdminConfigItem<AdminSenderState>> update(
      UUID id, AdminSenderInput input, boolean acknowledged, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminSenderState>> deactivate(
      UUID id, boolean acknowledged, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminSenderState>> delete(
      UUID id, boolean acknowledged, UUID userId, Long ifMatch);
}
