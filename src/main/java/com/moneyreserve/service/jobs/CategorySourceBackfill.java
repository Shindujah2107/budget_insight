package com.moneyreserve.service.jobs;

import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fills in {@code category_source} for rows stored before V1.17.0 that the migration could not
 * decide in SQL: rows in OTHER without a customer choice.
 *
 * <p>Whether such a row was put in OTHER deliberately (a message type or merchant rule says OTHER)
 * or only because nothing matched can be answered only by running the rules, so this runs them once
 * per row: {@code RULE} when today's rules deliberately say OTHER, otherwise {@code FALLBACK} (it
 * needs a category). The customer's category is never changed. Only rows whose source is still
 * NULL are touched, so it is idempotent and costs nothing once done.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategorySourceBackfill {

  private static final int BATCH = 200;

  private final BiTransactionRepository transactions;
  private final BiBankParserTemplateRepository templates;
  private final CategorisationService categorisation;
  private final TransactionTemplate tx;

  @EventListener(ApplicationReadyEvent.class)
  public void onStart() {
    try {
      int done = run();
      if (done > 0) {
        log.info("Insights category source backfilled for {} transaction(s)", done);
      }
    } catch (RuntimeException e) {
      // Never stop the service over a bookkeeping column; rows stay NULL and are retried next start.
      log.error("Insights category source backfill failed; will retry on next start", e);
    }
  }

  /** Backfills every row still missing a source. Returns how many were set. */
  public int run() {
    int total = 0;
    while (true) {
      Integer done = tx.execute(status -> batch());
      if (done == null || done == 0) {
        return total;
      }
      total += done;
    }
  }

  private int batch() {
    List<BiTransaction> rows = transactions.findWithoutCategorySource(PageRequest.of(0, BATCH));
    if (rows.isEmpty()) {
      return 0;
    }
    UUID other = categorisation.fallback().getId();
    Map<UUID, String> messageTypes = new HashMap<>();
    for (BiTransaction row : rows) {
      row.setCategorySource(sourceOf(row, other, messageTypes));
    }
    transactions.saveAll(rows);
    return rows.size();
  }

  private CategorySource sourceOf(BiTransaction row, UUID other, Map<UUID, String> messageTypes) {
    if (Boolean.TRUE.equals(row.getIsUserCategorised())) {
      return CategorySource.USER;
    }
    if (!other.equals(row.getCategoryId())) {
      // A category other than OTHER can only have come from a rule or the message type.
      return CategorySource.RULE;
    }
    String messageType =
        row.getParserTemplateId() == null
            ? null
            : messageTypes.computeIfAbsent(
                row.getParserTemplateId(),
                id -> templates.findById(id).map(BiBankParserTemplate::getMessageType).orElse(null));
    CategorisationService.Decision decision =
        categorisation.decide(
            new CategorisationInput(row.getMerchant(), messageType, row.getDirection()));
    boolean deliberateOther =
        decision.source() == CategorySource.RULE
            && Optional.ofNullable(decision.category())
                .map(BiCategory::getId)
                .map(other::equals)
                .orElse(false);
    return deliberateOther ? CategorySource.RULE : CategorySource.FALLBACK;
  }
}
