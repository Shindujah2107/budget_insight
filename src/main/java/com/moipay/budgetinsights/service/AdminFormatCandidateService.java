package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.AdminFormatCandidate;
import com.moipay.budgetinsights.dto.AdminFormatCandidateDraft;
import com.moipay.budgetinsights.dto.AdminPage;
import com.moipay.budgetinsights.dto.AdminSenderCoverage;
import com.moipay.budgetinsights.dto.AdminSmsVocabulary;
import com.moipay.budgetinsights.domain.FormatCandidateStatus;
import java.util.List;
import java.util.UUID;

/** SMS format discovery, admin side (Super Admin only). */
public interface AdminFormatCandidateService {

  /** Phase 1: unreadable money SMS per sender over the last {@code days} days. */
  List<AdminSenderCoverage> senderCoverage(int days);

  /** Phase 2: formats sent by at least the configured number of users (never fewer than 5). */
  AdminPage<AdminFormatCandidate> list(FormatCandidateStatus status, String senderId, int page, int size);

  AdminFormatCandidate get(UUID id);

  /** Phase 3: a sample built from the format with invented values, analysed by the wizard. */
  AdminFormatCandidateDraft draftTemplate(UUID id, String bankCode);

  AdminFormatCandidate reject(UUID id, String reason, UUID adminUserId);

  AdminSmsVocabulary vocabulary();
}
