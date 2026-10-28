package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiBank;
import com.moneyreserve.domain.BiSmsSenderAllowlist;
import com.moneyreserve.repository.BiBankRepository;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.AdminBank;
import com.moneyreserve.dto.AdminOverview;
import com.moneyreserve.dto.AdminOverview.BankCount;
import com.moneyreserve.dto.AdminOverview.CategoryCount;
import com.moneyreserve.dto.AdminOverview.DailyCount;
import com.moneyreserve.dto.AdminOverview.Kpis;
import com.moneyreserve.dto.AdminOverview.Range;
import com.moneyreserve.dto.AdminOverview.ReferenceVersions;
import com.moneyreserve.dto.AdminOverview.VersionCount;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiConsentRepository;
import com.moneyreserve.repository.BiDeviceSyncStateRepository;
import com.moneyreserve.repository.BiInsightCacheRepository;
import com.moneyreserve.repository.BiReferenceReleaseRepository;
import com.moneyreserve.repository.BiSmsSenderAllowlistRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.service.AdminOverviewService;
import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The Overview: five read-only panels, each one {@code GROUP BY} over existing rows, run in
 * parallel on a small bounded pool with a 5-second budget each. A panel that fails or times out is
 * reported in {@code panelErrors} and the others still render.
 */
@Slf4j
@Service
public class AdminOverviewServiceImpl implements AdminOverviewService {

  private static final int MAX_RANGE_DAYS = 366;
  private static final long PANEL_TIMEOUT_SECONDS = 5;
  private static final int DEVICE_WINDOW_DAYS = 90;

  private final BiTransactionRepository transactions;
  private final BiConsentRepository consents;
  private final BiCategoryRepository categories;
  private final BiSmsSenderAllowlistRepository senders;
  private final BiDeviceSyncStateRepository devices;
  private final BiInsightCacheRepository aggregates;
  private final BiReferenceReleaseRepository releases;
  private final InsightsProperties properties;
  private final BiBankRepository bankRegistry;
  private final ExecutorService pool = Executors.newFixedThreadPool(4);

  public AdminOverviewServiceImpl(
      BiTransactionRepository transactions,
      BiConsentRepository consents,
      BiCategoryRepository categories,
      BiSmsSenderAllowlistRepository senders,
      BiDeviceSyncStateRepository devices,
      BiInsightCacheRepository aggregates,
      BiReferenceReleaseRepository releases,
      InsightsProperties properties,
      BiBankRepository bankRegistry) {
    this.transactions = transactions;
    this.consents = consents;
    this.categories = categories;
    this.senders = senders;
    this.devices = devices;
    this.aggregates = aggregates;
    this.releases = releases;
    this.properties = properties;
    this.bankRegistry = bankRegistry;
  }

  @PreDestroy
  void shutdown() {
    pool.shutdownNow();
  }

  private record Headline(long customers, long consented, OffsetDateTime lastRefresh) {}

  @Override
  public AdminOverview overview(LocalDate fromParam, LocalDate toParam) {
    LocalDate today = LocalDate.now(properties.zoneId());
    LocalDate to = toParam != null ? toParam : today;
    LocalDate from = fromParam != null ? fromParam : to.minusDays(29);
    if (from.isAfter(to)
        || to.isAfter(today)
        || ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_RANGE_INVALID,
          "Choose a range of at most " + MAX_RANGE_DAYS + " days that does not end in the future.");
    }

    CompletableFuture<Headline> headline =
        panel(
            () ->
                new Headline(
                    transactions.countCustomersBetween(from, to),
                    consents.countActiveConsents(),
                    aggregates.findLatestComputedAt()));
    CompletableFuture<List<DailyCount>> daily = panel(() -> daily(from, to));
    CompletableFuture<List<CategoryCount>> byCategory =
        panel(() -> byCategory(from, to));
    CompletableFuture<List<BankCount>> byBank = panel(() -> byBank(from, to));
    CompletableFuture<ReferenceVersions> versions = panel(this::versions);

    List<String> errors = new ArrayList<>();
    Headline h = await("kpis", headline, errors);
    List<DailyCount> dailyRows = await("dailyBySource", daily, errors);
    List<CategoryCount> categoryRows = await("categories", byCategory, errors);
    List<BankCount> bankRows = await("banks", byBank, errors);
    ReferenceVersions versionRows = await("referenceVersions", versions, errors);
    log.info(
        "Budget Insights admin overview from={} to={} panelErrors={}", from, to, errors);

    return new AdminOverview(
        new Range(from, to),
        kpis(h, categoryRows, bankRows),
        dailyRows,
        categoryRows,
        bankRows,
        versionRows,
        errors);
  }

  // --- panels -----------------------------------------------------------------------------------

  private List<DailyCount> daily(LocalDate from, LocalDate to) {
    Map<LocalDate, EnumMap<TxnSource, Long>> byDay = new LinkedHashMap<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      byDay.put(d, new EnumMap<>(TxnSource.class));
    }
    for (Object[] row : transactions.countDailyBySource(from, to)) {
      EnumMap<TxnSource, Long> day = byDay.get((LocalDate) row[0]);
      if (day != null) {
        day.merge((TxnSource) row[1], ((Number) row[2]).longValue(), Long::sum);
      }
    }
    List<DailyCount> out = new ArrayList<>();
    byDay.forEach(
        (date, counts) ->
            out.add(
                new DailyCount(
                    date,
                    counts.getOrDefault(TxnSource.SMS_ANDROID, 0L),
                    counts.getOrDefault(TxnSource.SMS_IOS, 0L),
                    counts.getOrDefault(TxnSource.MANUAL, 0L))));
    return out;
  }

  private List<CategoryCount> byCategory(LocalDate from, LocalDate to) {
    Map<UUID, BiCategory> known = new HashMap<>();
    categories.findAll().forEach(c -> known.put(c.getId(), c));
    List<CategoryCount> out = new ArrayList<>();
    for (Object[] row : transactions.countByCategoryBetween(from, to)) {
      BiCategory c = known.get((UUID) row[0]);
      out.add(
          new CategoryCount(
              c == null ? "UNKNOWN" : c.getCode(),
              c == null ? "Unknown" : c.getNameEn(),
              c == null ? null : c.getColourHex(),
              ((Number) row[1]).longValue()));
    }
    out.sort(Comparator.comparingLong(CategoryCount::count).reversed());
    return out;
  }

  private List<BankCount> byBank(LocalDate from, LocalDate to) {
    Map<String, Long> counts = new HashMap<>();
    for (Object[] row : transactions.countByBankBetween(from, to)) {
      counts.put((String) row[0], ((Number) row[1]).longValue());
    }
    Set<String> codes = new TreeSet<>(counts.keySet());
    configuredBanks().forEach(codes::add);
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    List<BankCount> out = new ArrayList<>();
    for (String code : codes) {
      long n = counts.getOrDefault(code, 0L);
      out.add(
          new BankCount(
              code, bankName(code), n, total == 0 ? 0 : Math.round(n * 1000.0 / total) / 1000.0));
    }
    out.sort(Comparator.comparingLong(BankCount::count).reversed());
    return out;
  }

  private ReferenceVersions versions() {
    List<VersionCount> out = new ArrayList<>();
    OffsetDateTime since = OffsetDateTime.now().minusDays(DEVICE_WINDOW_DAYS);
    for (Object[] row : devices.countDevicesByVersionSince(since)) {
      out.add(new VersionCount(((Number) row[0]).intValue(), ((Number) row[1]).longValue()));
    }
    out.sort(Comparator.comparingInt(VersionCount::version).reversed());
    return new ReferenceVersions(Math.max(1, releases.findCurrentVersion()), out);
  }

  // --- assembly ---------------------------------------------------------------------------------

  private Kpis kpis(Headline h, List<CategoryCount> byCategory, List<BankCount> byBank) {
    Long stored = null;
    Long other = null;
    Double share = null;
    if (byCategory != null) {
      stored = byCategory.stream().mapToLong(CategoryCount::count).sum();
      other =
          byCategory.stream()
              .filter(c -> "OTHER".equals(c.code()))
              .mapToLong(CategoryCount::count)
              .sum();
      share = stored == 0 ? 0.0 : Math.round(other * 1000.0 / stored) / 1000.0;
    }
    Integer producing = null;
    Integer configured = null;
    List<AdminBank> silent = null;
    if (byBank != null) {
      Set<String> active = configuredBanks();
      configured = active.size();
      producing = (int) byBank.stream().filter(b -> b.count() > 0).count();
      silent =
          byBank.stream()
              .filter(b -> b.count() == 0 && active.contains(b.bankCode()))
              .map(b -> new AdminBank(b.bankCode(), b.bankName()))
              .toList();
    }
    return new Kpis(
        h == null ? null : h.customers(),
        h == null ? null : h.consented(),
        stored,
        share,
        other,
        producing,
        configured,
        silent,
        h == null ? null : h.lastRefresh());
  }

  /** Banks with at least one active sender: the ones handsets read SMS for. */
  private Set<String> configuredBanks() {
    Set<String> out = new TreeSet<>();
    senders.findAllByIsActiveTrue().stream()
        .map(BiSmsSenderAllowlist::getBankCode)
        .forEach(out::add);
    return out;
  }

  private String bankName(String code) {
    return bankRegistry.findById(code).map(BiBank::getBankName).orElse(code);
  }

  private <T> CompletableFuture<T> panel(Supplier<T> query) {
    return CompletableFuture.supplyAsync(query, pool);
  }

  private static <T> T await(String name, CompletableFuture<T> future, List<String> errors) {
    try {
      return future.get(PANEL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.info("Budget Insights admin overview panel {} interrupted", name);
      errors.add(name);
      return null;
    } catch (Exception ex) {
      future.cancel(true);
      log.error("Budget Insights admin overview panel {} failed", name, ex);
      errors.add(name);
      return null;
    }
  }
}
