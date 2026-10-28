package com.moneyreserve.service;

import com.moneyreserve.dto.AdminFormatCandidate;
import com.moneyreserve.dto.AdminFormatCandidateDraft;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminSenderCoverage;
import com.moneyreserve.dto.AdminSmsVocabulary;
import com.moneyreserve.domain.FormatCandidateStatus;
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
