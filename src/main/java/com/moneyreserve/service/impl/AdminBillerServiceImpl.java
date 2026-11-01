package com.moneyreserve.service.impl;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.ConfigChangeOp;
import com.moneyreserve.domain.ConfigEntityType;
import com.moneyreserve.dto.AdminBillerInput;
import com.moneyreserve.dto.AdminBillerState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminRecurringPayee;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.AdminBillerService;
import com.moneyreserve.service.calc.MerchantTerms;
import com.moneyreserve.service.calc.MerchantText;
import com.moneyreserve.service.impl.AdminEffectiveView.Item;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBillerServiceImpl implements AdminBillerService {

  /** A payee is listed only when this many customers pay it regularly: nobody is identifiable. */
  static final int MIN_CUSTOMERS = 3;

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminReleaseVersion releases;
  private final BiUserBillRepository userBills;
  private final InsightsProperties properties;

  @Override
  @Transactional(readOnly = true)
  public AdminConfigList<AdminConfigItem<AdminBillerState>> list(String q, String active) {
    Map<UUID, Long> followed = followed();
    List<AdminConfigItem<AdminBillerState>> items =
        view.billers().stream()
            .filter(i -> AdminListFilters.matchesActive(active, i.effective().active()))
            .filter(
                i ->
                    AdminListFilters.matchesText(
                        q, i.effective().code(), i.effective().nameEn(), i.effective().nameSi(), i.effective().nameTa()))
            .map(i -> toItem(i, followed))
            .toList();
    return new AdminConfigList<>(items, drafts.revision(), releases.current());
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminBillerState>> create(
      AdminBillerInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    AdminBillerState desired = toState(input);
    check(desired, null);
    UUID id = UUID.randomUUID();
    drafts.draft(ConfigEntityType.BILLER, id, ConfigChangeOp.CREATE, null, desired, userId);
    log.info("bi-admin biller create drafted id={} code={} by={}", id, desired.code(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminBillerState>> update(
      UUID id, AdminBillerInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminBillerState> item = require(id);
    requireSurvives(item);
    AdminBillerState desired = toState(input);
    if (item.published() != null && !item.published().code().equals(desired.code())) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_SYSTEM_ITEM_LOCKED,
          "A published biller code cannot change: customers' bills are keyed by it. Create a new biller instead.");
    }
    check(desired, id);
    ConfigChangeOp op = item.published() == null ? ConfigChangeOp.CREATE : opFor(item.published(), desired);
    drafts.draft(ConfigEntityType.BILLER, id, op, item.published(), desired, userId);
    log.info("bi-admin biller {} drafted id={} code={} by={}", op, id, desired.code(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminBillerState>> setActive(
      UUID id, boolean active, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminBillerState> item = require(id);
    requireSurvives(item);
    ConfigChangeOp op =
        item.published() == null
            ? ConfigChangeOp.CREATE
            : active ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE;
    drafts.draft(ConfigEntityType.BILLER, id, op, item.published(), item.effective().withActive(active), userId);
    log.info("bi-admin biller {} drafted id={} code={} by={}", op, id, item.effective().code(), userId);
    return result(id);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminBillerState>> delete(UUID id, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminBillerState> item = require(id);
    requireSurvives(item);
    if (item.published() != null && userBills.countByBillerId(id) > 0) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID, "Customers' bills come from this biller. Turn it off instead.");
    }
    drafts.draft(ConfigEntityType.BILLER, id, ConfigChangeOp.DELETE, item.published(), null, userId);
    log.info("bi-admin biller delete drafted id={} code={} by={}", id, item.effective().code(), userId);
    return result(id);
  }

  @Override
  @Transactional(readOnly = true)
  public List<AdminRecurringPayee> recurringPayees() {
    Map<String, Map<String, Long>> byPayee = new LinkedHashMap<>();
    for (Object[] row : userBills.countUnknownRecurringPayees()) {
      String payee = ((String) row[0]).substring(2);
      String cadence = row[1] instanceof BillCadence c ? c.name() : String.valueOf(row[1]);
      byPayee.computeIfAbsent(payee, k -> new HashMap<>()).merge(cadence, ((Number) row[2]).longValue(), Long::sum);
    }
    List<AdminRecurringPayee> out = new ArrayList<>();
    for (Map.Entry<String, Map<String, Long>> e : byPayee.entrySet()) {
      long customers = e.getValue().values().stream().mapToLong(Long::longValue).sum();
      if (customers < MIN_CUSTOMERS) {
        continue;
      }
      String cadence =
          e.getValue().entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
      out.add(new AdminRecurringPayee(e.getKey(), customers, cadence, suggestedTerms(e.getKey())));
    }
    out.sort(Comparator.comparingLong(AdminRecurringPayee::customers).reversed().thenComparing(AdminRecurringPayee::payee));
    log.info("bi-admin recurring payees listed count={}", out.size());
    return out;
  }

  // ---------------------------------------------------------------------------------------------

  /** The payee's words as one phrase keyword, if valid: a starting point the admin can edit. */
  private static List<String> suggestedTerms(String payee) {
    String phrase = String.join(" ", MerchantText.tokens(payee));
    return MerchantTerms.problem(phrase).isEmpty() && !phrase.isBlank() ? List.of(phrase) : List.of();
  }

  private Map<UUID, Long> followed() {
    Map<UUID, Long> out = new HashMap<>();
    for (Object[] row : userBills.countFollowedByBiller()) {
      out.put((UUID) row[0], ((Number) row[1]).longValue());
    }
    return out;
  }

  private AdminWriteResult<AdminConfigItem<AdminBillerState>> result(UUID id) {
    long revision = drafts.commit();
    Map<UUID, Long> followed = followed();
    return new AdminWriteResult<>(find(id).map(i -> toItem(i, followed)).orElse(null), revision);
  }

  private static AdminConfigItem<AdminBillerState> toItem(Item<AdminBillerState> i, Map<UUID, Long> followed) {
    return new AdminConfigItem<>(
        i.id(), i.state(), i.changeId(), i.published(), i.effective(), false, new Usage(followed.getOrDefault(i.id(), 0L)));
  }

  private Optional<Item<AdminBillerState>> find(UUID id) {
    return view.billers().stream().filter(i -> i.id().equals(id)).findFirst();
  }

  private Item<AdminBillerState> require(UUID id) {
    return find(id)
        .orElseThrow(() -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That biller does not exist."));
  }

  private static void requireSurvives(Item<AdminBillerState> item) {
    if (!item.survives()) {
      throw new BudgetInsightsException(
          ErrorCode.ADMIN_FIELD_INVALID, "This biller is waiting to be deleted. Discard that change first.");
    }
  }

  private void check(AdminBillerState desired, UUID self) {
    if (!properties.getAdmin().getIconKeys().contains(desired.iconKey())) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "The app has no icon called " + desired.iconKey() + ".",
          AdminFieldError.of("iconKey", "not one of the app's icons"));
    }
    boolean clash =
        view.billers().stream().anyMatch(i -> !i.id().equals(self) && i.effective().code().equals(desired.code()));
    if (clash) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_CONFIG_DUPLICATE,
          "A biller with the code " + desired.code() + " already exists.",
          AdminFieldError.of("code", "already used by another biller or pending draft"));
    }
    Optional<String> problem =
        MerchantTerms.listProblem(desired.terms())
            .or(() -> desired.terms().stream().map(MerchantTerms::problem).flatMap(Optional::stream).findFirst());
    if (problem.isPresent()) {
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Name terms are not valid: " + problem.get(),
          AdminFieldError.of("terms", problem.get()));
    }
  }

  private static AdminBillerState toState(AdminBillerInput in) {
    List<String> terms =
        in.terms().stream().map(t -> t.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT)).filter(t -> !t.isEmpty()).distinct().toList();
    return new AdminBillerState(
        in.code().trim().toUpperCase(Locale.ROOT),
        in.nameEn().trim(),
        blankToNull(in.nameSi()),
        blankToNull(in.nameTa()),
        in.billType(),
        terms,
        in.iconKey(),
        in.colourHex() == null || in.colourHex().isBlank() ? null : in.colourHex().toUpperCase(Locale.ROOT),
        in.tolerancePct(),
        in.sortOrder(),
        in.active());
  }

  private static ConfigChangeOp opFor(AdminBillerState published, AdminBillerState desired) {
    if (published.active() != desired.active() && Objects.equals(published.withActive(desired.active()), desired)) {
      return desired.active() ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE;
    }
    return ConfigChangeOp.UPDATE;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
