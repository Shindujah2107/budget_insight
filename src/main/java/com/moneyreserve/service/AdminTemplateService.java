package com.moneyreserve.service;

import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminTemplateAnalyseRequest;
import com.moneyreserve.dto.AdminTemplateAnalysis;
import com.moneyreserve.dto.AdminTemplateCheck;
import com.moneyreserve.dto.AdminTemplateGenerateRequest;
import com.moneyreserve.dto.AdminTemplateGenerated;
import com.moneyreserve.dto.AdminTemplateInput;
import com.moneyreserve.dto.AdminTemplateItem;
import com.moneyreserve.dto.AdminTemplateValidateRequest;
import com.moneyreserve.dto.AdminWriteResult;
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
