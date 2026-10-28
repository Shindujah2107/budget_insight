package com.moneyreserve.service;

import com.moneyreserve.dto.AdminBank;
import com.moneyreserve.dto.AdminConfigSummary;
import java.util.List;

/** Admin: shared Configuration reads (tab counters, banks, icon keys). */
public interface AdminConfigService {

  AdminConfigSummary summary();

  List<AdminBank> banks();

  List<String> iconKeys();

  /** Display name of a bank code; the code itself when unknown. */
  String bankName(String bankCode);

  /** Refuses a bank code that is not in the configured bank list. */
  void requireBank(String bankCode);
}
