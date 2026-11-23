package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiAskMrUsage;
import com.moneyreserve.repository.BiAskMrUsageRepository;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ask MR usage counts (V1.29.0): one row per question per day, counts only. Its own transaction,
 * so a lost count (two first-of-the-day inserts at once) never fails an answer; callers ignore its
 * errors.
 */
@Component
@RequiredArgsConstructor
public class AskMrUsageRecorder {

  public enum Event {
    SHOWN,
    EVIDENCE,
    DISMISSED
  }

  private final BiAskMrUsageRepository usage;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(LocalDate day, String question, Event event) {
    BiAskMrUsage row =
        usage.lock(day, question)
            .orElseGet(() -> BiAskMrUsage.builder().day(day).question(question).build());
    switch (event) {
      case SHOWN -> row.setShown(row.getShown() + 1);
      case EVIDENCE -> row.setEvidence(row.getEvidence() + 1);
      case DISMISSED -> row.setDismissed(row.getDismissed() + 1);
    }
    usage.saveAndFlush(row);
  }
}
