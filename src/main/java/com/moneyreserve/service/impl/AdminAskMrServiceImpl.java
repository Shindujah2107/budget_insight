package com.moneyreserve.service.impl;

import com.moneyreserve.domain.BiAskMrUsage;
import com.moneyreserve.domain.ConfigChangeOp;
import com.moneyreserve.domain.ConfigEntityType;
import com.moneyreserve.dto.AdminAskMrTipInput;
import com.moneyreserve.dto.AdminAskMrTipState;
import com.moneyreserve.dto.AdminConfigItem;
import com.moneyreserve.dto.AdminConfigList;
import com.moneyreserve.dto.AdminWriteResult;
import com.moneyreserve.exception.AdminFieldError;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAskMrUsageRepository;
import com.moneyreserve.service.impl.AdminEffectiveView.Item;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin › Spending › Ask MR (V1.29.0): the suggestion wording (draft → publish, like the rest of the
 * reference data) and usage counts. The switches and thresholds live on the budget policy.
 *
 * <p>Every edit keeps the tip's placeholders exactly (the app fills them in) and is refused when it
 * contains blaming words: Ask MR says what changed, never that the customer did wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAskMrServiceImpl {

  /** Words Ask MR never uses (English; matched as whole words, any case). */
  public static final List<String> BLAMING_WORDS =
      List.of(
          "irresponsible",
          "irresponsibly",
          "wasted",
          "wasteful",
          "wasting",
          "careless",
          "reckless",
          "bad habit",
          "bad habits",
          "foolish",
          "lazy",
          "shame",
          "overspent on purpose",
          "you failed",
          "should be ashamed");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)}");

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminReleaseVersion releases;
  private final BiAskMrUsageRepository usage;

  @Transactional(readOnly = true)
  public AdminConfigList<AdminConfigItem<AdminAskMrTipState>> tips() {
    List<AdminConfigItem<AdminAskMrTipState>> items = view.askMrTips().stream().map(AdminAskMrServiceImpl::toItem).toList();
    return new AdminConfigList<>(items, drafts.revision(), releases.current());
  }

  @Transactional
  public AdminWriteResult<AdminConfigItem<AdminAskMrTipState>> update(
      UUID id, AdminAskMrTipInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    Item<AdminAskMrTipState> item =
        find(id).orElseThrow(() -> new BudgetInsightsException(ErrorCode.ADMIN_CONFIG_NOT_FOUND, "That tip does not exist."));
    AdminAskMrTipState published = item.published();
    AdminAskMrTipState desired =
        new AdminAskMrTipState(
            published.code(),
            clean(input.textEn()),
            clean(input.textSi()),
            clean(input.textTa()),
            input.active(),
            published.sortOrder());
    check(published, desired);
    ConfigChangeOp op =
        published.active() != desired.active() && Objects.equals(published.withActive(desired.active()), desired)
            ? (desired.active() ? ConfigChangeOp.ACTIVATE : ConfigChangeOp.DEACTIVATE)
            : ConfigChangeOp.UPDATE;
    drafts.draft(ConfigEntityType.ASK_MR_TIP, id, op, published, desired, userId);
    log.info("bi-admin ask-mr tip {} drafted id={} code={} by={}", op, id, desired.code(), userId);
    long revision = drafts.commit();
    return new AdminWriteResult<>(find(id).map(AdminAskMrServiceImpl::toItem).orElse(null), revision);
  }

  /** Usage per question per day over the last {@code days} days (counts only). */
  @Transactional(readOnly = true)
  public List<BiAskMrUsage> usage(int days, LocalDate today) {
    int span = Math.max(1, Math.min(days, 366));
    return usage.findByDayBetweenOrderByDayDescQuestionAsc(today.minusDays(span - 1L), today);
  }

  // ---------------------------------------------------------------------------------------------

  /** The same placeholders as the published wording, in every language, and no blaming words. */
  static void check(AdminAskMrTipState published, AdminAskMrTipState desired) {
    Set<String> expected = placeholders(published.textEn());
    String[][] texts = {{"textEn", desired.textEn()}, {"textSi", desired.textSi()}, {"textTa", desired.textTa()}};
    for (String[] t : texts) {
      if (!placeholders(t[1]).equals(expected)) {
        throw BudgetInsightsException.withDetails(
            ErrorCode.ADMIN_FIELD_INVALID,
            "Keep exactly these placeholders: " + String.join(", ", expected.stream().map(p -> "{" + p + "}").toList()) + ".",
            AdminFieldError.of(t[0], "placeholders must be " + expected));
      }
      blaming(t[1])
          .ifPresent(
              w -> {
                throw BudgetInsightsException.withDetails(
                    ErrorCode.ADMIN_FIELD_INVALID,
                    "Ask Mate never blames: \"" + w + "\" isn't allowed. Say what changed and what could help.",
                    AdminFieldError.of(t[0], "contains \"" + w + "\""));
              });
    }
  }

  static Set<String> placeholders(String text) {
    Set<String> out = new TreeSet<>();
    Matcher m = PLACEHOLDER.matcher(text == null ? "" : text);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /** The first blaming word or phrase in {@code text}, matched as whole words. */
  public static Optional<String> blaming(String text) {
    String lower = " " + (text == null ? "" : text).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
    return BLAMING_WORDS.stream().filter(w -> lower.contains(" " + w + " ")).findFirst();
  }

  private Optional<Item<AdminAskMrTipState>> find(UUID id) {
    return view.askMrTips().stream().filter(i -> i.id().equals(id)).findFirst();
  }

  private static AdminConfigItem<AdminAskMrTipState> toItem(Item<AdminAskMrTipState> i) {
    return new AdminConfigItem<>(i.id(), i.state(), i.changeId(), i.published(), i.effective(), false, null);
  }

  private static String clean(String s) {
    return s.trim().replaceAll("\\s+", " ");
  }
}
