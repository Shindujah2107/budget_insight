package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminConfigList;
import com.moipay.budgetinsights.dto.AdminTemplateAnalyseRequest;
import com.moipay.budgetinsights.dto.AdminTemplateAnalysis;
import com.moipay.budgetinsights.dto.AdminTemplateCheck;
import com.moipay.budgetinsights.dto.AdminTemplateGenerateRequest;
import com.moipay.budgetinsights.dto.AdminTemplateGenerated;
import com.moipay.budgetinsights.dto.AdminTemplateInput;
import com.moipay.budgetinsights.dto.AdminTemplateItem;
import com.moipay.budgetinsights.dto.AdminTemplateValidateRequest;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import java.util.UUID;

/** Budget Insights admin — SMS Parser Templates (T1–T8, screens 14–22). */
public interface AdminTemplateService {

  AdminConfigList<AdminTemplateItem> list(String q, String active, String bank);

  AdminTemplateItem get(UUID id);

  AdminTemplateAnalysis analyse(AdminTemplateAnalyseRequest request);

  AdminTemplateGenerated generate(AdminTemplateGenerateRequest request);

  AdminTemplateCheck validate(AdminTemplateValidateRequest request);

  AdminWriteResult<AdminTemplateItem> create(AdminTemplateInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminTemplateItem> update(UUID id, AdminTemplateInput input, UUID userId, Long ifMatch);

  AdminWriteResult<AdminTemplateItem> retire(UUID id, UUID userId, Long ifMatch);
}
