package com.moneyreserve.service.impl;

import com.moneyreserve.repository.BiBudgetCategoryLimitRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.calc.MerchantRuleMatcher;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Usage counts shown beside configuration items ("1,540 transactions / 30 days").
 *
 * <p>Guidance numbers, not ledger values: each is cached for 60 seconds and dropped after a
 * publish. Counts only.
 */
@Component
@RequiredArgsConstructor
public class AdminUsageCounts {

  /** Last-30-days and all-time counts. */
  public record Counts(long recent, long total) {
    public static final Counts ZERO = new Counts(0, 0);
  }

  private record Cached(Object value, Instant at) {}

  private static final Duration TTL = Duration.ofSeconds(60);

  private final BiTransactionRepository transactions;
  private final BiBudgetCategoryLimitRepository limits;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public Map<UUID, Counts> transactionsByCategory() {
    return cached("category", () -> counts(transactions.countByCategorySince(since())));
  }

  public Map<UUID, Counts> transactionsByTemplate() {
    return cached("template", () -> counts(templateRows()));
  }

  /** One scan of the transactions for both the template counts and the last-read times. */
  private List<Object[]> templateRows() {
    return cached("templateRows", () -> transactions.countByTemplateSince(since()));
  }

  /** When each template last read an SMS: its newest stored transaction. */
  public Map<UUID, OffsetDateTime> lastTransactionByTemplate() {
    return cached(
        "templateLast",
        () -> {
          Map<UUID, OffsetDateTime> out = new HashMap<>();
          for (Object[] row : templateRows()) {
            OffsetDateTime at = row.length > 3 ? asOffsetDateTime(row[3]) : null;
            if (row[0] != null && at != null) {
              out.put((UUID) row[0], at);
            }
          }
          return out;
        });
  }

  public Map<UUID, Long> budgetLimitsByCategory() {
    return cached(
        "limits",
        () -> {
          Map<UUID, Long> out = new HashMap<>();
          for (Object[] row : limits.countByCategory()) {
            out.put((UUID) row[0], ((Number) row[1]).longValue());
          }
          return out;
        });
  }

  /** Transactions of the last 30 days per normalised merchant text (both directions). */
  public Map<String, Long> transactionsByMerchant() {
    return cached(
        "merchant",
        () -> {
          Map<String, Long> out = new HashMap<>();
          for (Object[] row : transactions.countByMerchantSince(since())) {
            String merchant = MerchantRuleMatcher.normalise((String) row[0]);
            if (merchant != null) {
              out.merge(merchant, ((Number) row[1]).longValue(), Long::sum);
            }
          }
          return out;
        });
  }

  private static OffsetDateTime asOffsetDateTime(Object value) {
    if (value instanceof OffsetDateTime o) {
      return o;
    }
    if (value instanceof Instant i) {
      return i.atOffset(java.time.ZoneOffset.UTC);
    }
    if (value instanceof java.sql.Timestamp t) {
      return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
    return null;
  }

  public void evict() {
    cache.clear();
  }

  private static LocalDate since() {
    return LocalDate.now().minusDays(30);
  }

  private static Map<UUID, Counts> counts(List<Object[]> rows) {
    Map<UUID, Counts> out = new HashMap<>();
    for (Object[] row : rows) {
      out.put(
          (UUID) row[0],
          new Counts(
              row[1] == null ? 0 : ((Number) row[1]).longValue(),
              ((Number) row[2]).longValue()));
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private <T> T cached(String key, Supplier<T> loader) {
    Instant now = Instant.now();
    Cached held = cache.get(key);
    if (held != null && now.isBefore(held.at().plus(TTL))) {
      return (T) held.value();
    }
    T value = loader.get();
    cache.put(key, new Cached(value, now));
    return value;
  }
}
