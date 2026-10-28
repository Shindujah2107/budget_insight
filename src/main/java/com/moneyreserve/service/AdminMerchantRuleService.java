package com.moneyreserve.service;

import com.moneyreserve.dto.AdminMerchantRuleInput;
import com.moneyreserve.dto.AdminMerchantRuleItem;
import com.moneyreserve.dto.AdminMerchantRuleTest;
import com.moneyreserve.dto.AdminMerchantRuleTestRequest;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminUncategorisedMerchant;
import com.moneyreserve.dto.AdminWriteResult;
import java.util.List;
import java.util.UUID;

/** Budget Insights admin — Merchant Rules (M1–M6, screens 05–10). */
public interface AdminMerchantRuleService {

  AdminPage<AdminMerchantRuleItem> list(
      String q, String matchType, UUID categoryId, String active, int page, int size);

  AdminMerchantRuleItem get(UUID id);

  AdminMerchantRuleTest test(AdminMerchantRuleTestRequest request);

  AdminWriteResult<AdminMerchantRuleItem> create(AdminMerchantRuleInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminMerchantRuleItem> update(
      UUID id, AdminMerchantRuleInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminMerchantRuleItem> delete(UUID id, UUID userId, Long ifMatch);

  /** Merchants left in OTHER over the last 30 days, most frequent first (layer 5). */
  List<AdminUncategorisedMerchant> uncategorised(int limit);
}
