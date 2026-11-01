package com.moneyreserve.service;

import com.moneyreserve.dto.AdminBillerInput;
import com.moneyreserve.dto.AdminBillerState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminRecurringPayee;
import com.moneyreserve.dto.AdminWriteResult;
import java.util.List;
import java.util.UUID;

/** Billers for the bills radar (Spending → Billers): drafts published like categories. */
public interface AdminBillerService {

  /** Customers following bills from this biller (dismissed ones not counted). */
  record Usage(long customers) {}

  AdminConfigList<AdminConfigItem<AdminBillerState>> list(String q, String active);

  AdminWriteResult<AdminConfigItem<AdminBillerState>> create(AdminBillerInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminBillerState>> update(
      UUID id, AdminBillerInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminConfigItem<AdminBillerState>> setActive(
      UUID id, boolean active, UUID userId, Long ifMatch);

  /** Refused while customers' bills point at the biller: turn it off instead. */
  AdminWriteResult<AdminConfigItem<AdminBillerState>> delete(UUID id, UUID userId, Long ifMatch);

  /** Payees recurring for at least the minimum number of customers that match no biller. */
  List<AdminRecurringPayee> recurringPayees();
}
