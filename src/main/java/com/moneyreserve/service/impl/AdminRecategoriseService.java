package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiRecategoriseMove;
import com.moneyreserve.domain.BiRecategoriseRun;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiRecategoriseMoveRepository;
import com.moneyreserve.repository.BiRecategoriseRunRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.CategorisationInput;
import com.moneyreserve.service.CategorisationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin "Re-sort old transactions" (V1.31.0): runs the current merchant rules over payments the app
 * categorised by itself, so older payments follow new categories (Groceries out of Shopping, Fuel
 * out of Transport…) and month-on-month comparisons stay fair.
 *
 * <ul>
 *   <li>Never touches a payment a person categorised ({@code isUserCategorised} or source USER),
 *       a money move, or a cash withdrawal.
 *   <li>Moves only where a rule decides a different, active category; never to Other (a payment
 *       nothing matches keeps the category it has).
 *   <li>Apply writes one log row per moved payment (old category and source); Undo puts back each
 *       payment still where the run left it and skips any changed since.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRecategoriseService {

  static final int PAGE = 500;
  private static final UUID START = new UUID(0L, 0L);

  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final CategorisationService categorisation;
  private final BiRecategoriseRunRepository runs;
  private final BiRecategoriseMoveRepository moves;
  private final BiInsightCacheRepository caches;

  /** One from → to group in the preview. */
  public record Group(Category from, Category to, long payments, long customers) {}

  public record Category(UUID id, String code, String name) {}

  /** What a run would do now: groups largest first, and how many hand-sorted payments stay. */
  public record Preview(long payments, long customers, long userSorted, List<Group> groups) {}

  public record Run(UUID id, OffsetDateTime createdAt, int moved, OffsetDateTime undoneAt) {}

  public record UndoResult(UUID runId, int restored, int skipped) {}

  private record Planned(BiTransaction txn, UUID to) {}

  @Transactional(readOnly = true)
  public Preview preview() {
    Map<UUID, BiCategory> cats = categoriesById();
    List<Planned> plan = plan(cats);
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, Set<UUID>> users = new HashMap<>();
    Set<UUID> everyone = new HashSet<>();
    for (Planned p : plan) {
      String key = p.txn().getCategoryId() + ">" + p.to();
      counts.computeIfAbsent(key, k -> new long[1])[0]++;
      users.computeIfAbsent(key, k -> new HashSet<>()).add(p.txn().getUserId());
      everyone.add(p.txn().getUserId());
    }
    List<Group> groups = new ArrayList<>();
    counts.forEach(
        (key, n) -> {
          String[] ids = key.split(">");
          groups.add(
              new Group(
                  category(cats.get(UUID.fromString(ids[0]))),
                  category(cats.get(UUID.fromString(ids[1]))),
                  n[0],
                  users.get(key).size()));
        });
    groups.sort(Comparator.comparingLong(Group::payments).reversed().thenComparing(g -> g.from().code() + g.to().code()));
    return new Preview(plan.size(), everyone.size(), transactions.countUserCategorised(), groups);
  }

  /** Moves every planned payment and logs each move under one run. */
  @Transactional
  public Run apply(UUID adminId) {
    List<Planned> plan = plan(categoriesById());
    BiRecategoriseRun run =
        runs.save(
            BiRecategoriseRun.builder()
                .id(UUID.randomUUID())
                .createdBy(adminId)
                .createdAt(OffsetDateTime.now())
                .moved(plan.size())
                .build());
    Set<String> invalidated = new HashSet<>();
    for (Planned p : plan) {
      BiTransaction t = p.txn();
      moves.save(
          BiRecategoriseMove.builder()
              .runId(run.getId())
              .transactionId(t.getId())
              .userId(t.getUserId())
              .fromCategoryId(t.getCategoryId())
              .toCategoryId(p.to())
              .fromSource(t.getCategorySource())
              .build());
      t.setCategoryId(p.to());
      t.setAutoCategoryId(p.to());
      t.setCategorySource(CategorySource.RULE);
      t.setUpdatedAt(OffsetDateTime.now());
      invalidate(t.getUserId(), t.getTxnDate(), invalidated);
    }
    transactions.saveAll(plan.stream().map(Planned::txn).toList());
    log.info("bi-admin re-sort applied run={} moved={} by={}", run.getId(), plan.size(), adminId);
    return toRun(run);
  }

  /** Puts back every payment the run moved that is still where the run left it. */
  @Transactional
  public UndoResult undo(UUID runId, UUID adminId) {
    BiRecategoriseRun run = runs.findById(runId).orElseThrow(() -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND));
    if (run.getUndoneAt() != null) {
      throw new BudgetInsightsException(ErrorCode.ADMIN_RESORT_ALREADY_UNDONE);
    }
    int restored = 0;
    int skipped = 0;
    Set<String> invalidated = new HashSet<>();
    for (BiRecategoriseMove m : moves.findAllByRunId(runId)) {
      BiTransaction t = transactions.findById(m.getTransactionId()).orElse(null);
      boolean untouched =
          t != null
              && t.getDeletedAt() == null
              && !Boolean.TRUE.equals(t.getIsUserCategorised())
              && t.getCategorySource() != CategorySource.USER
              && m.getToCategoryId().equals(t.getCategoryId());
      if (!untouched) {
        skipped++; // changed since (by the customer, or deleted): theirs now
        continue;
      }
      t.setCategoryId(m.getFromCategoryId());
      t.setAutoCategoryId(m.getFromCategoryId());
      t.setCategorySource(m.getFromSource());
      t.setUpdatedAt(OffsetDateTime.now());
      transactions.save(t);
      invalidate(t.getUserId(), t.getTxnDate(), invalidated);
      restored++;
    }
    run.setUndoneAt(OffsetDateTime.now());
    run.setUndoneBy(adminId);
    runs.save(run);
    log.info("bi-admin re-sort undone run={} restored={} skipped={} by={}", runId, restored, skipped, adminId);
    return new UndoResult(runId, restored, skipped);
  }

  @Transactional(readOnly = true)
  public List<Run> runs() {
    return runs.findTop20ByOrderByCreatedAtDesc().stream().map(AdminRecategoriseService::toRun).toList();
  }

  // ---------------------------------------------------------------------------------

  /** Every auto-categorised payment whose rule now says a different, active, non-Other category. */
  private List<Planned> plan(Map<UUID, BiCategory> cats) {
    List<Planned> out = new ArrayList<>();
    UUID after = START;
    while (true) {
      List<BiTransaction> page = transactions.findAutoCategorisedAfter(after, PageRequest.of(0, PAGE));
      if (page.isEmpty()) {
        break;
      }
      for (BiTransaction t : page) {
        BiCategory current = cats.get(t.getCategoryId());
        if (current == null || BiCategory.CASH_WITHDRAWAL_CODE.equals(current.getCode())) {
          continue;
        }
        CategorisationService.Decision d =
            categorisation.decide(new CategorisationInput(t.getMerchant(), null, t.getDirection()));
        BiCategory to = d.category();
        if (d.source() != CategorySource.RULE
            || to == null
            || !Boolean.TRUE.equals(to.getIsActive())
            || BiCategory.OTHER_CODE.equals(to.getCode())
            || to.getId().equals(current.getId())) {
          continue;
        }
        out.add(new Planned(t, to.getId()));
      }
      after = page.get(page.size() - 1).getId();
      if (page.size() < PAGE) {
        break;
      }
    }
    return out;
  }

  private Map<UUID, BiCategory> categoriesById() {
    Map<UUID, BiCategory> out = new HashMap<>();
    categories.findAll().forEach(c -> out.put(c.getId(), c));
    return out;
  }

  private void invalidate(UUID userId, LocalDate date, Set<String> done) {
    if (date != null && done.add(userId + "|" + date)) {
      caches.invalidateCovering(userId, date);
    }
  }

  private static Category category(BiCategory c) {
    return c == null ? null : new Category(c.getId(), c.getCode(), c.getNameEn());
  }

  private static Run toRun(BiRecategoriseRun r) {
    return new Run(r.getId(), r.getCreatedAt(), r.getMoved(), r.getUndoneAt());
  }
}
