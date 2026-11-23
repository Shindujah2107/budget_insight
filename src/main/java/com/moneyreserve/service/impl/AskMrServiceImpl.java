package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiAskMrTip;
import com.moneyreserve.domain.BiAskMrWorth;
import com.moneyreserve.domain.BiBudgetCategoryLimit;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.CategorySource;
import com.moneyreserve.dto.AskMrAnswer;
import com.moneyreserve.dto.AskMrCategories;
import com.moneyreserve.dto.AskMrAnswer.Driver;
import com.moneyreserve.dto.AskMrAnswer.Evidence;
import com.moneyreserve.dto.AskMrAnswer.Note;
import com.moneyreserve.dto.AskMrAnswer.Tip;
import com.moneyreserve.dto.AskMrQuestions;
import com.moneyreserve.dto.AskMrSettings;
import com.moneyreserve.dto.AskMrWorthKnowing;
import com.moneyreserve.dto.BudgetSummaryResponse;
import com.moneyreserve.exception.BudgetInsightsException;
import com.moneyreserve.exception.ErrorCode;
import com.moneyreserve.repository.BiAskMrTipRepository;
import com.moneyreserve.repository.BiAskMrWorthRepository;
import com.moneyreserve.repository.BiBudgetCategoryLimitRepository;
import com.moneyreserve.repository.BiBudgetRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import com.moneyreserve.service.BudgetService;
import com.moneyreserve.service.InsightsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ask MR (Premium Insights, V1.29.0): answers a few fixed questions about the customer's recorded
 * spending as structured facts (see {@link AskMrAnswer}); the app writes the sentences.
 *
 * <p>Rules, all deterministic and computed on each request from the transactions (nothing stored
 * but the "Worth knowing" log and usage counts):
 *
 * <ul>
 *   <li>Spending is {@link BiTransactionRepository#COMPARABLE_SPEND}, the same as "What changed?"
 *       and the category drilldown, so every figure matches the evidence it links to.
 *   <li>Month comparisons are this month's completed days against the same dates last month
 *       ({@link WhatChangedServiceImpl#periods}); before the early-month day they are TOO_EARLY.
 *       Weeks run Monday to today against the same days the week before.
 *   <li>A change is meaningful at both the minimum amount and the minimum percentage (admin).
 *   <li>A payment is a one-off when it is a yearly or quarterly bill, or at least the admin
 *       multiple of the category's median payment over the 90 days before (needs 3 payments).
 *       A change mostly made of a one-off gets no suggestion.
 *   <li>Facts only: merchants, categories, counts and amounts. Never a reason the data can't show.
 *   <li>Without Premium (and with the free teaser on) only the headline, notes and plain
 *       transaction evidence are sent.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AskMrServiceImpl {

  public enum Question {
    SPENDING_CHANGE,
    CATEGORY_CHANGE,
    WHERE_MONEY_WENT,
    BUDGET,
    IMPROVING,
    WEEK
  }

  static final int MIN_PREVIOUS_MONTH = 10;
  static final int MIN_CURRENT_MONTH = 3;
  static final int MIN_PREVIOUS_WEEK = 3;
  static final int MIN_CURRENT_WEEK = 1;
  static final int UNCATEGORISED_NOTE_PCT = 20;
  static final int SMS_GAP_DAYS = 5;
  static final int INCOME_LOOKBACK_DAYS = 35;
  static final int HISTORY_DAYS = 90;
  static final int REPEAT_DAYS = 14;
  static final int MAX_DRIVERS = 3;
  static final int MAX_PROGRESS = 2;
  static final int MAX_TIPS = 2;
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final UUID NONE = new UUID(0L, 0L);

  /** The seed wording (V1.29.0), used when a tip row is missing. */
  static final Map<String, String[]> DEFAULT_TIPS =
      Map.of(
          "set-limit",
          new String[] {
            "If you'd like {category} closer to {previousPeriod}'s level, you could set a monthly limit for it.",
            "{category} {previousPeriod} මට්ටමට ළං කර ගැනීමට කැමති නම්, ඒ සඳහා මාසික සීමාවක් සැකසිය හැක.",
            "{category} செலவை {previousPeriod} அளவுக்கு அருகில் கொண்டுவர விரும்பினால், அதற்கு ஒரு மாத வரம்பை அமைக்கலாம்."
          },
          "review-limit",
          new String[] {
            "If you'd like, you could review your {category} limit for the rest of the month.",
            "කැමති නම්, මාසයේ ඉතිරි කාලය සඳහා ඔබේ {category} සීමාව නැවත බැලිය හැක.",
            "விரும்பினால், மாதத்தின் மீதமுள்ள நாட்களுக்கு உங்கள் {category} வரம்பை மறுபரிசீலனை செய்யலாம்."
          },
          "one-fewer",
          new String[] {
            "One {merchant} purchase fewer a week would keep about {amount} a month.",
            "සතියකට {merchant} මිලදී ගැනීමක් අඩු කළොත් මසකට දළ වශයෙන් {amount}ක් ඉතිරි වේ.",
            "வாரத்திற்கு ஒரு {merchant} வாங்குதல் குறைத்தால் மாதத்திற்கு சுமார் {amount} மிச்சமாகும்."
          },
          "check-bill",
          new String[] {
            "If a {category} bill looks higher than you expected, it may be worth checking it with the provider.",
            "{category} බිල්පතක් ඔබ බලාපොරොත්තු වූවාට වඩා වැඩි ලෙස පෙනේ නම්, සේවා සපයන්නා සමඟ එය පරීක්ෂා කිරීම වටී.",
            "{category} கட்டணம் நீங்கள் எதிர்பார்த்ததை விட அதிகமாகத் தெரிந்தால், சேவை வழங்குநரிடம் சரிபார்ப்பது பயனுள்ளது."
          },
          "daily-pace",
          new String[] {
            "To finish the month within your target, about {amount} a day would do it.",
            "ඉලක්කය තුළ මාසය අවසන් කිරීමට, දිනකට දළ වශයෙන් {amount}ක් ප්‍රමාණවත් වේ.",
            "இலக்குக்குள் மாதத்தை முடிக்க, நாளொன்றுக்கு சுமார் {amount} போதுமானது."
          });

  private final InsightsProperties properties;
  private final BudgetPolicyProvider policy;
  private final PremiumEntitlements entitlements;
  private final WhatChangedServiceImpl whatChanged;
  private final BudgetService budgets;
  private final BiTransactionRepository transactions;
  private final BiCategoryRepository categories;
  private final BiBudgetRepository budgetRows;
  private final BiBudgetCategoryLimitRepository limits;
  private final BiUserBillRepository bills;
  private final BiAskMrTipRepository tips;
  private final BiAskMrWorthRepository worth;
  private final AskMrUsageRecorder usage;
  private final InsightsMapper mapper;

  private static int size(List<?> list) {
    return list == null ? 0 : list.size();
  }

  // ---------------------------------------------------------------------------------
  // Access and scope
  // ---------------------------------------------------------------------------------

  record Access(AskMrSettings settings, boolean entitled) {}

  /** Refuses when Ask MR is off, or for a customer without Premium when the teaser is off. */
  Access access(UUID userId) {
    AskMrSettings s = policy.current().askMr();
    if (!s.enabled()) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_DISABLED);
    }
    boolean entitled = entitlements.hasPremium(userId, today());
    if (!entitled && !s.freeTeaser()) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_PREMIUM);
    }
    return new Access(s, entitled);
  }

  record Scope(List<UUID> chosen, boolean all, Collection<UUID> ids, String currency) {}

  private Scope scope(UUID userId, List<UUID> accountIds) {
    List<UUID> chosen = whatChanged.ownedAccounts(userId, accountIds);
    boolean all = chosen.isEmpty();
    return new Scope(chosen, all, all ? List.of(NONE) : chosen, properties.getCurrency());
  }

  /** A current period and the one it is compared with (both inclusive). */
  public record Window(LocalDate from, LocalDate to, LocalDate prevFrom, LocalDate prevTo) {}

  /** This month's completed days against the same dates last month; null on the 1st. */
  public static Window monthWindow(LocalDate today) {
    WhatChangedServiceImpl.Periods p = WhatChangedServiceImpl.periods(YearMonth.from(today), today);
    return p.curStart() == null ? null : new Window(p.curStart(), p.curEnd(), p.prevStart(), p.prevEnd());
  }

  /**
   * The week (Monday to Sunday) holding {@code within} (default today): this week runs to today,
   * an earlier week is whole; each against the same days the week before. A future date is today.
   */
  public static Window weekWindow(LocalDate today, LocalDate within) {
    LocalDate ref = within == null || within.isAfter(today) ? today : within;
    LocalDate start = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate end = start.plusDays(6).isBefore(today) ? start.plusDays(6) : today;
    return new Window(start, end, start.minusWeeks(1), end.minusWeeks(1));
  }

  // ---------------------------------------------------------------------------------
  // Questions
  // ---------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public AskMrQuestions questions(UUID userId, List<UUID> accountIds) {
    Access a = access(userId);
    Scope sc = scope(userId, accountIds);
    LocalDate today = today();
    AskMrSettings s = a.settings();
    Window month = monthWindow(today);
    LocalDate earliest = transactions.earliestRecordedOn(userId, sc.all(), sc.ids());

    String monthReason;
    if (month == null || today.getDayOfMonth() < s.earlyMonthDay()) {
      monthReason = "TOO_EARLY";
    } else if (!enough(userId, sc, month, earliest, MIN_PREVIOUS_MONTH, MIN_CURRENT_MONTH)) {
      monthReason = "NOT_ENOUGH_DATA";
    } else {
      monthReason = null;
    }
    Window week = weekWindow(today, null);
    String weekReason =
        enough(userId, sc, week, earliest, MIN_PREVIOUS_WEEK, MIN_CURRENT_WEEK) ? null : "NOT_ENOUGH_DATA";
    boolean anyThisMonth =
        !list(userId, sc, YearMonth.from(today).atDay(1), today).isEmpty();

    List<AskMrQuestions.Question> out = new ArrayList<>();
    out.add(question(Question.SPENDING_CHANGE, monthReason));
    out.add(question(Question.WHERE_MONEY_WENT, anyThisMonth ? null : "NOT_ENOUGH_DATA"));
    out.add(question(Question.BUDGET, null));
    out.add(question(Question.IMPROVING, monthReason));
    out.add(question(Question.WEEK, weekReason));
    return AskMrQuestions.builder().entitled(a.entitled()).weeklySummary(s.weeklySummary()).questions(out).build();
  }

  /**
   * "Compare a category": this month's completed days by category, largest share first, each with
   * the change against the same dates last month ({@code changed} when meaningful).
   */
  @Transactional(readOnly = true)
  public AskMrCategories categories(UUID userId, List<UUID> accountIds) {
    Access a = access(userId);
    Scope sc = scope(userId, accountIds);
    Window w = monthWindow(today());
    if (w == null) {
      return AskMrCategories.builder().status("TOO_EARLY").currency(sc.currency()).items(List.of()).build();
    }
    List<BiTransaction> cur = list(userId, sc, w.from(), w.to());
    List<BiTransaction> prev = list(userId, sc, w.prevFrom(), w.prevTo());
    Map<UUID, BiCategory> cats = categoriesOf(cur, prev);
    Map<UUID, List<BiTransaction>> curBy = byCategory(cur);
    Map<UUID, List<BiTransaction>> prevBy = byCategory(prev);
    BigDecimal total = sum(cur);
    List<AskMrCategories.Item> items = new ArrayList<>();
    for (Map.Entry<UUID, List<BiTransaction>> e : curBy.entrySet()) {
      if (e.getKey() == null || NONE.equals(e.getKey()) || !cats.containsKey(e.getKey())) {
        continue; // uncategorised spend is reviewed on its own, not compared
      }
      BigDecimal amount = sum(e.getValue());
      BigDecimal before = sum(prevBy.getOrDefault(e.getKey(), List.of()));
      BigDecimal diff = amount.subtract(before);
      items.add(
          AskMrCategories.Item.builder()
              .category(category(e.getKey(), cats))
              .amount(mapper.money(amount))
              .share(share(amount, total))
              .count(e.getValue().size())
              .delta(mapper.money(diff))
              .changed(meaningful(diff, before, a.settings()))
              .build());
    }
    items.sort(
        Comparator.comparing((AskMrCategories.Item i) -> new BigDecimal(i.getAmount()))
            .reversed()
            .thenComparing(i -> i.getCategory().getCode() == null ? "" : i.getCategory().getCode()));
    return AskMrCategories.builder()
        .status("ANSWERED")
        .period(range(w.from(), w.to()))
        .comparePeriod(range(w.prevFrom(), w.prevTo()))
        .currency(sc.currency())
        .total(mapper.money(total))
        .items(items)
        .build();
  }

  private static AskMrQuestions.Question question(Question q, String reason) {
    return AskMrQuestions.Question.builder().id(q.name()).available(reason == null).reason(reason).build();
  }

  private boolean enough(UUID userId, Scope sc, Window w, LocalDate earliest, int minPrev, int minCur) {
    if (earliest == null || earliest.isAfter(w.prevFrom())) {
      return false;
    }
    return list(userId, sc, w.prevFrom(), w.prevTo()).size() >= minPrev
        && list(userId, sc, w.from(), w.to()).size() >= minCur;
  }

  // ---------------------------------------------------------------------------------
  // Answers
  // ---------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public AskMrAnswer answer(
      UUID userId, String questionId, UUID categoryId, LocalDate week, List<UUID> accountIds) {
    Access a = access(userId);
    Question q = parse(questionId);
    Scope sc = scope(userId, accountIds);
    LocalDate today = today();
    AskMrSettings s = a.settings();
    AskMrAnswer answer =
        switch (q) {
          case SPENDING_CHANGE -> monthChange(userId, sc, s, today, Question.SPENDING_CHANGE);
          case IMPROVING -> monthChange(userId, sc, s, today, Question.IMPROVING);
          case CATEGORY_CHANGE -> categoryChange(userId, sc, s, today, requireCategory(categoryId));
          case WHERE_MONEY_WENT -> whereMoneyWent(userId, sc, today);
          case BUDGET -> budget(userId, sc, s, today);
          case WEEK -> change(userId, sc, s, today, weekWindow(today, week), Question.WEEK, MIN_PREVIOUS_WEEK, MIN_CURRENT_WEEK);
        };
    answer.setCurrency(sc.currency());
    answer.setAccountIds(sc.chosen());
    if (!a.entitled()) {
      lock(answer);
    }
    count(q.name(), AskMrUsageRecorder.Event.SHOWN);
    log.info("Ask MR answer userId={} question={} status={} locked={}", userId, q, answer.getStatus(), !a.entitled());
    return answer;
  }

  /** The Sunday summary: "How was my week?" for the week holding {@code week} (default this one). */
  @Transactional(readOnly = true)
  public AskMrAnswer weekly(UUID userId, LocalDate week, List<UUID> accountIds) {
    Access a = access(userId);
    if (!a.settings().weeklySummary()) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_DISABLED);
    }
    return answer(userId, Question.WEEK.name(), null, week, accountIds);
  }

  /** Opening a piece of evidence (counted per question; nothing about the customer is stored). */
  public void evidenceOpened(UUID userId, String questionId) {
    access(userId);
    count(parse(questionId).name(), AskMrUsageRecorder.Event.EVIDENCE);
  }

  private static Question parse(String id) {
    try {
      return Question.valueOf(id == null ? "" : id.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_BAD_REQUEST);
    }
  }

  private BiCategory requireCategory(UUID categoryId) {
    if (categoryId == null) {
      throw new BudgetInsightsException(ErrorCode.ASK_MR_BAD_REQUEST);
    }
    return categories.findById(categoryId).orElseThrow(() -> new BudgetInsightsException(ErrorCode.ASK_MR_BAD_REQUEST));
  }

  /** The free teaser: what happened, and where to look; never the why or the suggestions. */
  private static void lock(AskMrAnswer a) {
    a.setPremiumLocked(true);
    a.setLockedPreview(
        AskMrAnswer.LockedPreview.builder()
            .reasons(size(a.getDrivers()) + size(a.getProgress()))
            .suggestions(size(a.getTips()))
            .build());
    a.setDrivers(null);
    a.setProgress(null);
    a.setTips(null);
    a.setFollowUps(null);
    a.setSideBySide(null);
    a.setForecast(null);
    a.setTrend(null);
    a.setTopPlaces(null);
    if (a.getEvidence() != null) {
      a.setEvidence(a.getEvidence().stream().filter(e -> "TRANSACTIONS".equals(e.getKind()) || "BUDGET".equals(e.getKind())).toList());
    }
  }

  // ---------------------------------------------------------------------------------
  // Spending change (month, week) and improvements
  // ---------------------------------------------------------------------------------

  private AskMrAnswer monthChange(UUID userId, Scope sc, AskMrSettings s, LocalDate today, Question q) {
    Window w = monthWindow(today);
    if (w == null || today.getDayOfMonth() < s.earlyMonthDay()) {
      return tooEarly(q, w, today);
    }
    return change(userId, sc, s, today, w, q, MIN_PREVIOUS_MONTH, MIN_CURRENT_MONTH);
  }

  private AskMrAnswer change(
      UUID userId, Scope sc, AskMrSettings s, LocalDate today, Window w, Question q, int minPrev, int minCur) {
    List<BiTransaction> cur = list(userId, sc, w.from(), w.to());
    List<BiTransaction> prev = list(userId, sc, w.prevFrom(), w.prevTo());
    LocalDate earliest = transactions.earliestRecordedOn(userId, sc.all(), sc.ids());
    AskMrAnswer.AskMrAnswerBuilder out = base(q, w);
    if (earliest == null || earliest.isAfter(w.prevFrom()) || prev.size() < minPrev || cur.size() < minCur) {
      return out.status("NOT_ENOUGH_DATA").build();
    }
    Map<UUID, BiCategory> cats = categoriesOf(cur, prev);
    Map<UUID, List<BiTransaction>> curBy = byCategory(cur);
    Map<UUID, List<BiTransaction>> prevBy = byCategory(prev);
    Map<UUID, List<BiTransaction>> history =
        byCategory(list(userId, sc, w.from().minusDays(HISTORY_DAYS), w.from().minusDays(1)));
    Map<UUID, BiUserBill> billsById = billsOf(cur);

    // Per-category changes that pass both thresholds, largest first.
    List<CategoryChange> changes = new ArrayList<>();
    Set<UUID> ids = new HashSet<>(curBy.keySet());
    ids.addAll(prevBy.keySet());
    for (UUID id : ids) {
      List<BiTransaction> c = curBy.getOrDefault(id, List.of());
      List<BiTransaction> p = prevBy.getOrDefault(id, List.of());
      BigDecimal diff = sum(c).subtract(sum(p));
      if (meaningful(diff, sum(p), s)) {
        changes.add(new CategoryChange(id, c, p, diff));
      }
    }
    changes.sort(Comparator.comparing((CategoryChange c) -> c.diff().abs()).reversed().thenComparing(c -> c.id().toString()));

    BigDecimal total = sum(cur);
    BigDecimal before = sum(prev);
    BigDecimal diff = total.subtract(before);
    List<Driver> progress = new ArrayList<>();
    for (CategoryChange c : changes) {
      if (c.diff().signum() < 0 && progress.size() < (q == Question.IMPROVING ? MAX_DRIVERS : MAX_PROGRESS)) {
        progress.add(categoryDriver("CATEGORY_DOWN", c, cats));
      }
    }

    List<Driver> drivers = new ArrayList<>();
    List<Evidence> evidence = new ArrayList<>();
    List<Tip> tipsOut = new ArrayList<>();
    List<AskMrAnswer.FollowUp> follow = new ArrayList<>();
    Map<String, BiAskMrTip> tipRows = tipRows();

    if (q == Question.IMPROVING) {
      BigDecimal saved = progress.isEmpty() ? BigDecimal.ZERO : changes.stream().filter(c -> c.diff().signum() < 0).limit(MAX_DRIVERS).map(c -> c.diff().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
      out.headline(
          AskMrAnswer.Headline.builder()
              .kind(progress.isEmpty() ? "NO_PROGRESS" : "PROGRESS")
              .subject("TOTAL")
              .amount(mapper.money(saved))
              .build());
      changes.stream().filter(c -> c.diff().signum() < 0).findFirst().ifPresent(c -> evidence.add(transactionsEvidence(c.id(), cats, w.from(), w.to(), sc)));
      out.progress(progress).drivers(List.of());
    } else {
      out.headline(totalHeadline(diff, total, before, cur.size(), prev.size(), s, q == Question.WEEK ? "WEEK" : "TOTAL"));
      boolean down = diff.signum() < 0 && meaningful(diff, before, s);
      for (CategoryChange c : changes) {
        if (drivers.size() >= MAX_DRIVERS) {
          break;
        }
        if (down ? c.diff().signum() >= 0 : c.diff().signum() <= 0) {
          continue;
        }
        BiCategory changed = cats.get(c.id());
        if (!down && BiCategory.TYPE_BILL.equals(typeOf(changed))) {
          // A bill category that rose: "a bigger bill", not extra spending; no cut-back tip.
          drivers.add(categoryDriver("BILL_UP", c, cats));
          if (follow.isEmpty()) {
            follow.add(AskMrAnswer.FollowUp.builder().question(Question.CATEGORY_CHANGE.name()).category(category(c.id(), cats)).build());
          }
          continue;
        }
        Driver one = down ? null : oneOff(c.current(), history.getOrDefault(c.id(), List.of()), billsById, s);
        if (one != null && dominates(one, c.diff())) {
          one.setCategory(category(c.id(), cats));
          drivers.add(one);
          evidence.add(transactionEvidence(one.getTransactionId()));
        } else {
          drivers.add(categoryDriver(down ? "CATEGORY_DOWN" : "CATEGORY_UP", c, cats));
          if (evidence.stream().noneMatch(e -> "TRANSACTIONS".equals(e.getKind()))) {
            evidence.add(transactionsEvidence(c.id(), cats, w.from(), w.to(), sc));
          }
          if (!down && q == Question.SPENDING_CHANGE && tipsOut.isEmpty() && cutBackTipFits(changed)) {
            tipsOut.add(limitTip(userId, c.id(), cats, w, today, tipRows));
          }
          if (!down && follow.isEmpty()) {
            follow.add(AskMrAnswer.FollowUp.builder().question(Question.CATEGORY_CHANGE.name()).category(category(c.id(), cats)).build());
          }
        }
      }
      if (down) {
        progress.clear(); // the headline is the good news; the drivers say where it came from
      } else if (!progress.isEmpty()) {
        follow.add(AskMrAnswer.FollowUp.builder().question(Question.IMPROVING.name()).build());
      }
      out.progress(progress).drivers(drivers);
    }

    if (q != Question.IMPROVING) {
      out.sideBySide(sideBySide(w, cur, prev, false));
    }
    if (q == Question.SPENDING_CHANGE) {
      YearMonth last = YearMonth.from(w.from()).minusMonths(1);
      out.forecast(forecast(w, cur, sum(list(userId, sc, last.atDay(1), last.atEndOfMonth())), drivers, billIds(cats)));
      out.trend(trend(userId, sc, w, null, earliest));
    }
    List<Note> notes = notes(userId, sc, cur, total, w, today);
    if (notes.stream().anyMatch(n -> "UNCATEGORISED_HIGH".equals(n.getKind()))) {
      evidence.add(Evidence.builder().kind("UNCATEGORISED").from(w.from()).to(w.to()).build());
    }
    return out.status("ANSWERED").tips(tipsOut).evidence(evidence).notes(notes).followUps(follow).build();
  }

  private AskMrAnswer.Headline totalHeadline(
      BigDecimal diff, BigDecimal total, BigDecimal before, long n, long nBefore, AskMrSettings s, String subject) {
    String kind = !meaningful(diff, before, s) ? "STEADY" : diff.signum() > 0 ? "INCREASE" : "DECREASE";
    return AskMrAnswer.Headline.builder()
        .kind(kind)
        .subject(subject)
        .amount(mapper.money(total))
        .previous(mapper.money(before))
        .delta(mapper.money(diff))
        .percent(percent(diff, before))
        .count(n)
        .previousCount(nBefore)
        .build();
  }

  // ---------------------------------------------------------------------------------
  // One category
  // ---------------------------------------------------------------------------------

  private AskMrAnswer categoryChange(UUID userId, Scope sc, AskMrSettings s, LocalDate today, BiCategory cat) {
    Window w = monthWindow(today);
    if (w == null || today.getDayOfMonth() < s.earlyMonthDay()) {
      return tooEarly(Question.CATEGORY_CHANGE, w, today);
    }
    AskMrAnswer.AskMrAnswerBuilder out = base(Question.CATEGORY_CHANGE, w);
    LocalDate earliest = transactions.earliestRecordedOn(userId, sc.all(), sc.ids());
    List<BiTransaction> cur = only(cat.getId(), list(userId, sc, w.from(), w.to()));
    List<BiTransaction> prev = only(cat.getId(), list(userId, sc, w.prevFrom(), w.prevTo()));
    if (earliest == null || earliest.isAfter(w.prevFrom()) || cur.size() + prev.size() == 0) {
      return out.status("NOT_ENOUGH_DATA").build();
    }
    Map<UUID, BiCategory> cats = Map.of(cat.getId(), cat);
    List<BiTransaction> history = only(cat.getId(), list(userId, sc, w.from().minusDays(HISTORY_DAYS), w.from().minusDays(1)));
    BigDecimal total = sum(cur);
    BigDecimal before = sum(prev);
    BigDecimal diff = total.subtract(before);
    AskMrAnswer.Headline h = totalHeadline(diff, total, before, cur.size(), prev.size(), s, "CATEGORY");
    h.setCategory(category(cat.getId(), cats));
    out.headline(h);

    boolean up = "INCREASE".equals(h.getKind());
    boolean down = "DECREASE".equals(h.getKind());
    List<Driver> drivers = new ArrayList<>();
    List<Evidence> evidence = new ArrayList<>();
    List<Tip> tipsOut = new ArrayList<>();
    List<Driver> progress = new ArrayList<>();
    String type = typeOf(cat);
    boolean bill = BiCategory.TYPE_BILL.equals(type);
    boolean excluded = BiCategory.TYPE_EXCLUDED.equals(type);
    // Bills are compared bill by bill: a rise is a bigger bill, never a habit or a one-off.
    Driver one = up && !bill ? oneOff(cur, history, billsOf(cur), s) : null;
    // Named only when it explains the change (half or more of it) or is a yearly bill; a smaller
    // large payment is just part of the month.
    if (one != null && !"YEARLY_BILL".equals(one.getReason()) && !dominates(one, diff)) {
      one = null;
    }
    if (one != null) {
      one.setCategory(category(cat.getId(), cats));
      drivers.add(one);
    }
    if (bill) {
      drivers.addAll(billDrivers(cur, prev, up || down));
    } else if (up || down) {
      // The one-off is explained on its own: patterns (more visits, bigger payments, a new
      // merchant) are read from the rest, so the same payment is never counted twice.
      UUID oneOffId = one == null ? null : one.getTransactionId();
      List<BiTransaction> usual = cur.stream().filter(t -> !t.getId().equals(oneOffId)).toList();
      drivers.addAll(mixDrivers(usual, prev, history, up, s));
    }
    while (drivers.size() > MAX_DRIVERS) {
      drivers.remove(drivers.size() - 1);
    }
    evidence.add(transactionsEvidence(cat.getId(), cats, w.from(), w.to(), sc));
    if (one != null) {
      evidence.add(transactionEvidence(one.getTransactionId()));
    }
    evidence.add(Evidence.builder().kind("BUDGET").month(YearMonth.from(today).toString()).category(category(cat.getId(), cats)).build());
    boolean doubled = drivers.stream().anyMatch(d -> "DOUBLE_PAYMENT".equals(d.getKind()));
    if (bill && (up || doubled)) {
      // "Check the bill", never "use less".
      tipsOut.add(tip(tipRows(), "check-bill", Map.of("category", cat.getCode())));
    } else if (up && cutBackTipFits(cat) && (one == null || !dominates(one, diff))) {
      Map<String, BiAskMrTip> rows = tipRows();
      drivers.stream()
          .filter(d -> BiCategory.TYPE_FLEXIBLE.equals(type)) // one fewer visit: for spending by choice only
          .filter(d -> "MORE_PURCHASES".equals(d.getKind()) && d.getMerchant() != null && d.getAveragePayment() != null)
          .findFirst()
          .ifPresent(
              d ->
                  tipsOut.add(
                      tip(
                          rows,
                          "one-fewer",
                          Map.of("merchant", d.getMerchant(), "amount", mapper.money(new BigDecimal(d.getAveragePayment()).multiply(BigDecimal.valueOf(4)))))));
      tipsOut.add(limitTip(userId, cat.getId(), cats, w, today, rows));
    }
    if (down) {
      progress.add(
          Driver.builder()
              .kind("CATEGORY_DOWN")
              .category(category(cat.getId(), cats))
              .amount(mapper.money(total))
              .previousAmount(mapper.money(before))
              .delta(mapper.money(diff))
              .count((long) cur.size())
              .previousCount((long) prev.size())
              .build());
    }
    List<AskMrAnswer.FollowUp> follow = List.of(AskMrAnswer.FollowUp.builder().question(Question.SPENDING_CHANGE.name()).build());
    YearMonth last = YearMonth.from(w.from()).minusMonths(1);
    out.sideBySide(sideBySide(w, cur, prev, true))
        // Bills arrive once a month and money moved isn't spending: a pace would mislead for both.
        .forecast(bill || excluded ? null : forecast(w, cur, sum(only(cat.getId(), list(userId, sc, last.atDay(1), last.atEndOfMonth()))), drivers, Set.of()))
        .trend(trend(userId, sc, w, cat.getId(), earliest))
        .topPlaces(topPlaces(cur));
    return out.status("ANSWERED")
        .drivers(drivers)
        .progress(progress)
        .tips(tipsOut.stream().limit(MAX_TIPS).toList())
        .evidence(evidence)
        .notes(notes(userId, sc, list(userId, sc, w.from(), w.to()), null, w, today))
        .followUps(follow)
        .build();
  }

  /**
   * Within one category: more or fewer purchases (the merchant with the biggest change in count,
   * or the category when no merchant stands out), a new merchant, and bigger or smaller payments.
   */
  List<Driver> mixDrivers(List<BiTransaction> cur, List<BiTransaction> prev, List<BiTransaction> history, boolean up, AskMrSettings s) {
    List<Driver> out = new ArrayList<>();
    Map<String, List<BiTransaction>> curM = byMerchant(cur);
    Map<String, List<BiTransaction>> prevM = byMerchant(prev);
    Set<String> seenBefore = new HashSet<>(prevM.keySet());
    seenBefore.addAll(byMerchant(history).keySet());
    String best = null;
    int bestGap = 0;
    Set<String> keys = new HashSet<>(curM.keySet());
    keys.addAll(prevM.keySet());
    for (String k : keys) {
      if (!clearName(k)) {
        continue; // "POS: 2 more" says nothing; the count for the whole category is said instead
      }
      int gap = curM.getOrDefault(k, List.of()).size() - prevM.getOrDefault(k, List.of()).size();
      int signed = up ? gap : -gap;
      if (signed > bestGap || (signed == bestGap && signed > 0 && best != null && k.compareTo(best) < 0)) {
        best = k;
        bestGap = signed;
      }
    }
    if (best != null) {
      List<BiTransaction> c = curM.getOrDefault(best, List.of());
      List<BiTransaction> p = prevM.getOrDefault(best, List.of());
      boolean isNew = up && !seenBefore.contains(best);
      out.add(
          Driver.builder()
              .kind(isNew ? "NEW_MERCHANT" : up ? "MORE_PURCHASES" : "FEWER_PURCHASES")
              .merchant(displayMerchant(c.isEmpty() ? p : c))
              .count((long) c.size())
              .previousCount((long) p.size())
              .amount(mapper.money(sum(c)))
              .previousAmount(mapper.money(sum(p)))
              .delta(mapper.money(sum(c).subtract(sum(p))))
              .averagePayment(average(c))
              .build());
    } else if (cur.size() != prev.size()) {
      out.add(
          Driver.builder()
              .kind(cur.size() > prev.size() ? "MORE_PURCHASES" : "FEWER_PURCHASES")
              .count((long) cur.size())
              .previousCount((long) prev.size())
              .build());
    }
    if (!cur.isEmpty() && !prev.isEmpty()) {
      BigDecimal avgC = sum(cur).divide(BigDecimal.valueOf(cur.size()), 2, RoundingMode.HALF_UP);
      BigDecimal avgP = sum(prev).divide(BigDecimal.valueOf(prev.size()), 2, RoundingMode.HALF_UP);
      BigDecimal effect = avgC.subtract(avgP).multiply(BigDecimal.valueOf(cur.size()));
      boolean tenPct = avgP.signum() > 0 && avgC.subtract(avgP).abs().multiply(BigDecimal.TEN).compareTo(avgP) >= 0;
      boolean matters = effect.abs().multiply(BigDecimal.valueOf(2)).compareTo(BigDecimal.valueOf(s.minChangeAmount())) >= 0;
      if (tenPct && matters && (avgC.compareTo(avgP) > 0) == up) {
        out.add(
            Driver.builder()
                .kind(up ? "BIGGER_PURCHASES" : "SMALLER_PURCHASES")
                .averagePayment(mapper.money(avgC))
                .previousAveragePayment(mapper.money(avgP))
                .delta(mapper.money(effect))
                .build());
      }
    }
    return out;
  }

  // ---------------------------------------------------------------------------------
  // Side by side, month-end estimate, trend, top places (Premium)
  // ---------------------------------------------------------------------------------

  static final int MAX_PAYMENTS = 5;
  static final int MAX_PLACES = 3;
  static final int TREND_MONTHS = 3;
  static final int MIN_FORECAST_PAYMENTS = 3;

  /** Words that name no one: a merchant made only of these (and codes) is "unclear". */
  private static final Set<String> GENERIC_WORDS =
      Set.of(
          "POS", "ATM", "EFTPOS", "CARD", "DEBIT", "CREDIT", "PURCHASE", "PURCHASES", "PAYMENT", "PAYMENTS",
          "PMT", "TXN", "TRANSACTION", "TRN", "TRANSFER", "FUND", "FUNDS", "ONLINE", "IPG", "ECOM", "UNKNOWN",
          "MERCHANT", "NA", "WDL", "WITHDRAWAL", "CASH", "CEFT", "SLIPS", "LKR", "REF", "TERMINAL", "TID", "MID",
          "SALE", "SALES", "VISA", "MASTER", "MASTERCARD", "AMEX", "TO", "AT", "FOR", "FROM", "LK", "LKA", "SL",
          "OTHER", "MISC");

  /**
   * Whether a recorded merchant name says who was paid. Empty names, "POS", "POS 4411", terminal
   * or bank codes ("TID 0042", "IPG-77812") are unclear: Ask MR then speaks of purchases, not a name.
   */
  static boolean clearName(String merchant) {
    if (merchant == null) {
      return false;
    }
    String m = merchant.trim().toUpperCase(Locale.ROOT);
    long letters = m.chars().filter(Character::isLetter).count();
    long digits = m.chars().filter(Character::isDigit).count();
    if (letters < 3 || digits > letters) {
      return false;
    }
    for (String word : m.split("[^\\p{L}\\p{N}]+")) {
      if (word.isEmpty() || word.chars().anyMatch(Character::isDigit)) {
        continue;
      }
      if (!GENERIC_WORDS.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /** Count and total for each period; with {@code payments}, the payments too (newest first). */
  private AskMrAnswer.SideBySide sideBySide(Window w, List<BiTransaction> cur, List<BiTransaction> prev, boolean payments) {
    return AskMrAnswer.SideBySide.builder()
        .current(side(w.from(), w.to(), cur, payments))
        .previous(side(w.prevFrom(), w.prevTo(), prev, payments))
        .build();
  }

  private AskMrAnswer.Side side(LocalDate from, LocalDate to, List<BiTransaction> rows, boolean payments) {
    AskMrAnswer.Side.SideBuilder out =
        AskMrAnswer.Side.builder().from(from).to(to).count(rows.size()).amount(mapper.money(sum(rows)));
    if (payments) {
      out.payments(
          rows.stream()
              .sorted(
                  Comparator.comparing(BiTransaction::getTxnDate, Comparator.reverseOrder())
                      .thenComparing(BiTransaction::getAmount, Comparator.reverseOrder())
                      .thenComparing(t -> t.getId().toString()))
              .limit(MAX_PAYMENTS)
              .map(
                  t ->
                      AskMrAnswer.Payment.builder()
                          .transactionId(t.getId())
                          .date(t.getTxnDate())
                          .merchant(clearName(t.getMerchant()) ? t.getMerchant().trim() : null)
                          .amount(mapper.money(t.getAmount()))
                          .build())
              .toList());
    }
    return out.build();
  }

  /**
   * Where the month ends at this pace: the completed days scaled to the whole month, rounded to
   * 100. Left out with fewer than three payments (too jumpy), when a one-off explains the change
   * (it won't repeat), or when the month is already complete. Payments in {@code billCategories}
   * count as they are, not scaled: a bill paid on the 3rd doesn't come again every 3 days.
   */
  private AskMrAnswer.Forecast forecast(
      Window w, List<BiTransaction> cur, BigDecimal lastMonth, List<Driver> drivers, Set<UUID> billCategories) {
    if (cur.size() < MIN_FORECAST_PAYMENTS || drivers.stream().anyMatch(d -> "ONE_OFF".equals(d.getKind()))) {
      return null;
    }
    YearMonth month = YearMonth.from(w.from());
    int days = w.to().getDayOfMonth();
    if (days >= month.lengthOfMonth()) {
      return null;
    }
    List<BiTransaction> bills = cur.stream().filter(t -> billCategories.contains(t.getCategoryId())).toList();
    List<BiTransaction> paced = cur.stream().filter(t -> !billCategories.contains(t.getCategoryId())).toList();
    BigDecimal pace =
        sum(paced)
            .multiply(BigDecimal.valueOf(month.lengthOfMonth()))
            .divide(BigDecimal.valueOf(days), 0, RoundingMode.HALF_UP)
            .add(sum(bills));
    BigDecimal rounded = pace.divide(HUNDRED, 0, RoundingMode.HALF_UP).multiply(HUNDRED);
    return AskMrAnswer.Forecast.builder()
        .month(month.toString())
        .amount(mapper.money(rounded))
        .previousMonth(mapper.money(lastMonth))
        .build();
  }

  /**
   * The last three full months and this month's completed days, oldest first, for one category or
   * everything. A month before the first recorded day is left out (it would look like a drop);
   * fewer than two full months → none.
   */
  private List<AskMrAnswer.TrendPoint> trend(UUID userId, Scope sc, Window w, UUID categoryId, LocalDate earliest) {
    YearMonth month = YearMonth.from(w.from());
    YearMonth first = month.minusMonths(TREND_MONTHS);
    List<BiTransaction> rows = list(userId, sc, first.atDay(1), w.to());
    if (categoryId != null) {
      rows = only(categoryId, rows);
    }
    Map<YearMonth, BigDecimal> totals = new HashMap<>();
    for (BiTransaction t : rows) {
      totals.merge(YearMonth.from(t.getTxnDate()), t.getAmount(), BigDecimal::add);
    }
    List<AskMrAnswer.TrendPoint> out = new ArrayList<>();
    for (YearMonth m = first; !m.isAfter(month); m = m.plusMonths(1)) {
      if (earliest == null || earliest.isAfter(m.atDay(1))) {
        continue;
      }
      out.add(
          AskMrAnswer.TrendPoint.builder()
              .month(m.toString())
              .amount(mapper.money(totals.getOrDefault(m, BigDecimal.ZERO)))
              .partial(m.equals(month))
              .build());
    }
    return out.size() >= 3 ? out : null;
  }

  private static Set<UUID> billIds(Map<UUID, BiCategory> cats) {
    Set<UUID> out = new HashSet<>();
    cats.forEach((id, c) -> {
      if (BiCategory.TYPE_BILL.equals(c.getSpendType())) {
        out.add(id);
      }
    });
    return out;
  }

  static final int DOUBLE_PAYMENT_DAYS = 7;

  /**
   * One bill category, bill by bill: each named bill (clear merchant name; unnamed payments
   * together) against the same dates before, largest change first. A possible double payment
   * comes first: the same named bill for the same amount twice within a week.
   */
  List<Driver> billDrivers(List<BiTransaction> cur, List<BiTransaction> prev, boolean changed) {
    List<Driver> out = new ArrayList<>();
    Driver twice = doublePayment(cur);
    if (twice != null) {
      out.add(twice);
    }
    if (!changed) {
      return out;
    }
    Map<String, List<BiTransaction>> curB = billGroups(cur);
    Map<String, List<BiTransaction>> prevB = billGroups(prev);
    Set<String> keys = new HashSet<>(curB.keySet());
    keys.addAll(prevB.keySet());
    List<Driver> bills = new ArrayList<>();
    for (String k : keys) {
      List<BiTransaction> c = curB.getOrDefault(k, List.of());
      List<BiTransaction> p = prevB.getOrDefault(k, List.of());
      BigDecimal diff = sum(c).subtract(sum(p));
      if (diff.signum() == 0) {
        continue;
      }
      bills.add(
          Driver.builder()
              .kind(diff.signum() > 0 ? "BILL_UP" : "BILL_DOWN")
              .merchant(k.isEmpty() ? null : displayMerchant(c.isEmpty() ? p : c))
              .amount(mapper.money(sum(c)))
              .previousAmount(mapper.money(sum(p)))
              .delta(mapper.money(diff))
              .count((long) c.size())
              .previousCount((long) p.size())
              .build());
    }
    bills.sort(
        Comparator.comparing((Driver d) -> new BigDecimal(d.getDelta()).abs())
            .reversed()
            .thenComparing(d -> d.getMerchant() == null ? "" : d.getMerchant()));
    out.addAll(bills.stream().limit(Math.max(0, MAX_DRIVERS - out.size())).toList());
    return out;
  }

  /** Clear names grouped (ignoring case and spacing); payments without a clear name under "". */
  private static Map<String, List<BiTransaction>> billGroups(List<BiTransaction> rows) {
    Map<String, List<BiTransaction>> out = new LinkedHashMap<>();
    for (BiTransaction t : rows) {
      String m = t.getMerchant() == null ? "" : t.getMerchant().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
      out.computeIfAbsent(clearName(m) ? m : "", k -> new ArrayList<>()).add(t);
    }
    return out;
  }

  /** The same named bill, same amount, twice within a week: worth a look, nothing more. */
  private Driver doublePayment(List<BiTransaction> cur) {
    for (Map.Entry<String, List<BiTransaction>> e : byMerchant(cur).entrySet()) {
      if (!clearName(e.getKey())) {
        continue;
      }
      List<BiTransaction> rows =
          e.getValue().stream().sorted(Comparator.comparing(BiTransaction::getTxnDate).thenComparing(t -> t.getId().toString())).toList();
      for (int i = 1; i < rows.size(); i++) {
        BiTransaction a = rows.get(i - 1);
        BiTransaction b = rows.get(i);
        if (a.getAmount().compareTo(b.getAmount()) == 0
            && java.time.temporal.ChronoUnit.DAYS.between(a.getTxnDate(), b.getTxnDate()) <= DOUBLE_PAYMENT_DAYS) {
          return Driver.builder()
              .kind("DOUBLE_PAYMENT")
              .merchant(displayMerchant(e.getValue()))
              .amount(mapper.money(b.getAmount()))
              .count(2L)
              .date(a.getTxnDate())
              .transactionId(b.getId())
              .build();
        }
      }
    }
    return null;
  }

  /** The category's biggest merchants this period (clear names only), with their share. */
  private List<AskMrAnswer.Place> topPlaces(List<BiTransaction> cur) {
    BigDecimal total = sum(cur);
    List<AskMrAnswer.Place> out =
        byMerchant(cur).entrySet().stream()
            .filter(e -> clearName(e.getKey()))
            .sorted(
                Comparator.comparing((Map.Entry<String, List<BiTransaction>> e) -> sum(e.getValue()))
                    .reversed()
                    .thenComparing(Map.Entry::getKey))
            .limit(MAX_PLACES)
            .map(
                e ->
                    AskMrAnswer.Place.builder()
                        .merchant(displayMerchant(e.getValue()))
                        .amount(mapper.money(sum(e.getValue())))
                        .count(e.getValue().size())
                        .share(share(sum(e.getValue()), total))
                        .build())
            .toList();
    return out.isEmpty() ? null : out;
  }

  // ---------------------------------------------------------------------------------
  // Where the money went, budget
  // ---------------------------------------------------------------------------------

  private AskMrAnswer whereMoneyWent(UUID userId, Scope sc, LocalDate today) {
    LocalDate from = YearMonth.from(today).atDay(1);
    List<BiTransaction> cur = list(userId, sc, from, today);
    AskMrAnswer.AskMrAnswerBuilder out =
        AskMrAnswer.builder().question(Question.WHERE_MONEY_WENT.name()).period(range(from, today));
    if (cur.isEmpty()) {
      return out.status("NOT_ENOUGH_DATA").build();
    }
    BigDecimal total = sum(cur);
    Map<UUID, BiCategory> cats = categoriesOf(cur, List.of());
    List<Driver> drivers = new ArrayList<>();
    List<Map.Entry<UUID, List<BiTransaction>>> ranked = new ArrayList<>(byCategory(cur).entrySet());
    ranked.sort(Comparator.comparing((Map.Entry<UUID, List<BiTransaction>> e) -> sum(e.getValue())).reversed().thenComparing(e -> e.getKey().toString()));
    for (Map.Entry<UUID, List<BiTransaction>> e : ranked.stream().limit(MAX_DRIVERS).toList()) {
      drivers.add(
          Driver.builder()
              .kind("TOP_CATEGORY")
              .category(category(e.getKey(), cats))
              .amount(mapper.money(sum(e.getValue())))
              .count((long) e.getValue().size())
              .share(share(sum(e.getValue()), total))
              .build());
    }
    byMerchant(cur).entrySet().stream()
        .filter(e -> clearName(e.getKey()))
        .max(Comparator.comparing((Map.Entry<String, List<BiTransaction>> e) -> sum(e.getValue())).thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
        .ifPresent(
            e ->
                drivers.add(
                    Driver.builder()
                        .kind("TOP_MERCHANT")
                        .merchant(displayMerchant(e.getValue()))
                        .amount(mapper.money(sum(e.getValue())))
                        .count((long) e.getValue().size())
                        .share(share(sum(e.getValue()), total))
                        .build()));
    List<Evidence> evidence = new ArrayList<>();
    if (!ranked.isEmpty()) {
      evidence.add(transactionsEvidence(ranked.get(0).getKey(), cats, from, today, sc));
    }
    Window w = new Window(from, today, null, null);
    return out.status("ANSWERED")
        .headline(AskMrAnswer.Headline.builder().kind("SUMMARY").subject("TOTAL").amount(mapper.money(total)).count((long) cur.size()).build())
        .drivers(drivers)
        .progress(List.of())
        .tips(List.of())
        .evidence(evidence)
        .notes(notes(userId, sc, cur, total, w, today))
        .followUps(List.of(AskMrAnswer.FollowUp.builder().question(Question.SPENDING_CHANGE.name()).build()))
        .build();
  }

  private AskMrAnswer budget(UUID userId, Scope sc, AskMrSettings s, LocalDate today) {
    YearMonth month = YearMonth.from(today);
    BudgetSummaryResponse summary = budgets.summary(new Caller(userId), month);
    LocalDate from = month.atDay(1);
    AskMrAnswer.AskMrAnswerBuilder out =
        AskMrAnswer.builder().question(Question.BUDGET.name()).period(range(from, month.atEndOfMonth())).status("ANSWERED");
    List<Evidence> evidence = new ArrayList<>();
    evidence.add(Evidence.builder().kind("BUDGET").month(month.toString()).build());
    List<Note> notes = new ArrayList<>();
    if (transactions.countIncomeSince(userId, today.minusDays(INCOME_LOOKBACK_DAYS)) == 0) {
      notes.add(Note.builder().kind("INCOME_INCOMPLETE").build());
    }
    BigDecimal spent = new BigDecimal(summary.getSpentToDate());
    if (!summary.isBudgetSet()) {
      return out.headline(
              AskMrAnswer.Headline.builder().kind("NO_TARGET").subject("BUDGET").amount(mapper.money(spent)).daysRemaining(summary.getDaysRemaining()).build())
          .drivers(List.of())
          .progress(List.of())
          .tips(List.of())
          .evidence(evidence)
          .notes(notes)
          .build();
    }
    BigDecimal target = new BigDecimal(summary.getMonthlyTarget());
    BigDecimal delta = spent.subtract(target);
    out.headline(
        AskMrAnswer.Headline.builder()
            .kind(delta.signum() > 0 ? "OVER_TARGET" : "UNDER_TARGET")
            .subject("BUDGET")
            .amount(mapper.money(spent))
            .previous(mapper.money(target))
            .delta(mapper.money(delta))
            .percent(target.signum() > 0 ? spent.multiply(HUNDRED).divide(target, 1, RoundingMode.HALF_UP).doubleValue() : null)
            .daysRemaining(summary.getDaysRemaining())
            .build());

    List<Driver> drivers = new ArrayList<>();
    List<BiTransaction> month2date = list(userId, sc, from, today);
    Map<UUID, List<BiTransaction>> history = byCategory(list(userId, sc, from.minusDays(HISTORY_DAYS), from.minusDays(1)));
    Map<UUID, BiUserBill> billsById = billsOf(month2date);
    Driver biggestOneOff = null;
    for (Map.Entry<UUID, List<BiTransaction>> e : byCategory(month2date).entrySet()) {
      Driver one = oneOff(e.getValue(), history.getOrDefault(e.getKey(), List.of()), billsById, s);
      if (one != null && (biggestOneOff == null || new BigDecimal(one.getAmount()).compareTo(new BigDecimal(biggestOneOff.getAmount())) > 0)) {
        one.setCategory(category(e.getKey(), categoriesOf(e.getValue(), List.of())));
        biggestOneOff = one;
      }
    }
    if (biggestOneOff != null) {
      drivers.add(biggestOneOff);
      evidence.add(transactionEvidence(biggestOneOff.getTransactionId()));
    }
    Map<String, BiCategory> byCode = new HashMap<>();
    categories.findAll().forEach(c -> byCode.put(c.getCode(), c));
    List<BudgetSummaryResponse.CategoryAlert> alerts = summary.getCategoryAlerts() == null ? List.of() : summary.getCategoryAlerts();
    for (BudgetSummaryResponse.CategoryAlert al : alerts) {
      if (drivers.size() >= MAX_DRIVERS) {
        break;
      }
      BiCategory c = byCode.get(al.getCategoryCode());
      drivers.add(
          Driver.builder()
              .kind("EXCEEDED".equals(al.getState()) ? "OVER_LIMIT" : "NEAR_LIMIT")
              .category(c == null ? AskMrAnswer.Category.builder().code(al.getCategoryCode()).build() : toCategory(c))
              .amount(al.getSpent())
              .limit(al.getLimit())
              .build());
    }
    List<Tip> tipsOut = new ArrayList<>();
    Map<String, BiAskMrTip> rows = tipRows();
    Integer daysLeft = summary.getDaysRemaining();
    if (delta.signum() < 0 && daysLeft != null && daysLeft > 0) {
      BigDecimal perDay = delta.negate().divide(BigDecimal.valueOf(daysLeft), 2, RoundingMode.DOWN);
      tipsOut.add(tip(rows, "daily-pace", Map.of("amount", mapper.money(perDay))));
    }
    alerts.stream()
        .filter(al -> "EXCEEDED".equals(al.getState()))
        .findFirst()
        .ifPresent(
            al -> {
              tipsOut.add(tip(rows, "review-limit", Map.of("category", al.getCategoryCode())));
              BiCategory c = byCode.get(al.getCategoryCode());
              if (c != null) {
                evidence.add(transactionsEvidence(c.getId(), Map.of(c.getId(), c), from, today, sc));
              }
            });
    return out.drivers(drivers).progress(List.of()).tips(tipsOut.stream().limit(MAX_TIPS).toList()).evidence(evidence).notes(notes).build();
  }

  // ---------------------------------------------------------------------------------
  // "Worth knowing"
  // ---------------------------------------------------------------------------------

  /**
   * One meaningful category change this month worth a card, or none: at most the admin's number a
   * week, never a topic shown in the last 14 days, and the same card all day once shown.
   */
  @Transactional
  public AskMrWorthKnowing worthKnowing(UUID userId, List<UUID> accountIds) {
    Access a = access(userId);
    AskMrSettings s = a.settings();
    LocalDate today = today();
    Window w = monthWindow(today);
    if (w == null || today.getDayOfMonth() < s.earlyMonthDay() || s.worthKnowingPerWeek() == 0) {
      return new AskMrWorthKnowing();
    }
    Scope sc = scope(userId, accountIds);
    List<BiAskMrWorth> recent = worth.findByUserIdAndShownOnGreaterThanEqual(userId, today.minusDays(REPEAT_DAYS - 1L));
    BiAskMrWorth todays = recent.stream().filter(r -> r.getShownOn().equals(today)).findFirst().orElse(null);
    if (todays != null && todays.getDismissedAt() != null) {
      return new AskMrWorthKnowing();
    }
    long thisWeek = recent.stream().filter(r -> !r.getShownOn().isBefore(today.minusDays(6))).count();
    if (todays == null && thisWeek >= s.worthKnowingPerWeek()) {
      return new AskMrWorthKnowing();
    }
    LocalDate earliest = transactions.earliestRecordedOn(userId, sc.all(), sc.ids());
    List<BiTransaction> cur = list(userId, sc, w.from(), w.to());
    List<BiTransaction> prev = list(userId, sc, w.prevFrom(), w.prevTo());
    if (earliest == null || earliest.isAfter(w.prevFrom()) || prev.size() < MIN_PREVIOUS_MONTH || cur.size() < MIN_CURRENT_MONTH) {
      return new AskMrWorthKnowing();
    }
    Map<UUID, BiCategory> cats = categoriesOf(cur, prev);
    Map<UUID, List<BiTransaction>> curBy = byCategory(cur);
    Map<UUID, List<BiTransaction>> prevBy = byCategory(prev);
    Set<UUID> ids = new HashSet<>(curBy.keySet());
    ids.addAll(prevBy.keySet());
    List<CategoryChange> changes = new ArrayList<>();
    for (UUID id : ids) {
      List<BiTransaction> c = curBy.getOrDefault(id, List.of());
      List<BiTransaction> p = prevBy.getOrDefault(id, List.of());
      BigDecimal diff = sum(c).subtract(sum(p));
      if (cats.get(id) != null && meaningful(diff, sum(p), s)) {
        changes.add(new CategoryChange(id, c, p, diff));
      }
    }
    changes.sort(Comparator.comparing((CategoryChange c) -> c.diff().abs()).reversed().thenComparing(c -> c.id().toString()));
    Set<String> shownTopics = new HashSet<>();
    recent.stream().filter(r -> r != todays).forEach(r -> shownTopics.add(r.getTopic()));
    for (CategoryChange c : changes) {
      String topic = "CATEGORY:" + cats.get(c.id()).getCode() + ":" + (c.diff().signum() > 0 ? "UP" : "DOWN");
      if (todays != null ? !todays.getTopic().equals(topic) : shownTopics.contains(topic)) {
        continue;
      }
      BiAskMrWorth row = todays;
      if (row == null) {
        row = worth.save(BiAskMrWorth.builder().id(UUID.randomUUID()).userId(userId).topic(topic).shownOn(today).build());
      }
      return AskMrWorthKnowing.builder()
          .card(
              AskMrWorthKnowing.Card.builder()
                  .id(row.getId())
                  .topic(topic)
                  .kind(c.diff().signum() > 0 ? "INCREASE" : "DECREASE")
                  .category(category(c.id(), cats))
                  .delta(mapper.money(c.diff().abs()))
                  .period(range(w.from(), w.to()))
                  .comparePeriod(range(w.prevFrom(), w.prevTo()))
                  .question(Question.CATEGORY_CHANGE.name())
                  .build())
          .build();
    }
    return new AskMrWorthKnowing();
  }

  @Transactional
  public void dismissWorthKnowing(UUID userId, UUID id) {
    access(userId);
    BiAskMrWorth row = worth.findByIdAndUserId(id, userId).orElseThrow(() -> new BudgetInsightsException(ErrorCode.ASK_MR_NOT_FOUND));
    if (row.getDismissedAt() == null) {
      row.setDismissedAt(OffsetDateTime.now());
      worth.save(row);
      count("WORTH_KNOWING", AskMrUsageRecorder.Event.DISMISSED);
    }
  }

  // ---------------------------------------------------------------------------------
  // Building blocks
  // ---------------------------------------------------------------------------------

  record CategoryChange(UUID id, List<BiTransaction> current, List<BiTransaction> previous, BigDecimal diff) {}

  /** Both thresholds: at least the amount, and (against a non-zero earlier period) the percentage. */
  static boolean meaningful(BigDecimal diff, BigDecimal previous, AskMrSettings s) {
    if (diff.abs().compareTo(BigDecimal.valueOf(s.minChangeAmount())) < 0 || diff.signum() == 0) {
      return false;
    }
    return previous.signum() <= 0 || diff.abs().multiply(HUNDRED).compareTo(previous.multiply(BigDecimal.valueOf(s.minChangePct()))) >= 0;
  }

  /**
   * The largest one-off among {@code current}: a yearly or quarterly bill payment, or a payment of
   * at least the admin multiple of the median payment in {@code history} (3 or more payments).
   */
  Driver oneOff(List<BiTransaction> current, List<BiTransaction> history, Map<UUID, BiUserBill> billsById, AskMrSettings s) {
    BigDecimal median = median(history);
    BigDecimal floor = BigDecimal.valueOf(s.minChangeAmount());
    BiTransaction best = null;
    String reason = null;
    // A merchant paid more than once this period is a pattern, not a one-off.
    Map<String, List<BiTransaction>> byMerchant = byMerchant(current);
    for (BiTransaction t : current) {
      BiUserBill bill = t.getUserBillId() == null ? null : billsById.get(t.getUserBillId());
      boolean rareBill = bill != null && (bill.getCadence() == BillCadence.YEARLY || bill.getCadence() == BillCadence.QUARTERLY);
      String merchant = t.getMerchant() == null ? "" : t.getMerchant().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
      // An unclear name ("POS") groups unrelated shops, so it says nothing about repeats.
      boolean once = !clearName(merchant) || byMerchant.getOrDefault(merchant, List.of()).size() <= 1;
      boolean large =
          median != null
              && once
              && t.getAmount().compareTo(floor) >= 0
              && t.getAmount().multiply(BigDecimal.TEN).compareTo(median.multiply(BigDecimal.valueOf(s.oneOffTenths()))) >= 0;
      if ((rareBill || large) && (best == null || t.getAmount().compareTo(best.getAmount()) > 0)) {
        best = t;
        reason = rareBill ? "YEARLY_BILL" : "LARGE_PAYMENT";
      }
    }
    if (best == null) {
      return null;
    }
    return Driver.builder()
        .kind("ONE_OFF")
        .merchant(clearName(best.getMerchant()) ? best.getMerchant().trim() : null)
        .amount(mapper.money(best.getAmount()))
        .averagePayment(median == null ? null : mapper.money(median))
        .transactionId(best.getId())
        .date(best.getTxnDate())
        .reason(reason)
        .build();
  }

  /** A one-off that makes up at least half of an increase explains it. */
  private static boolean dominates(Driver oneOff, BigDecimal diff) {
    return diff.signum() > 0 && new BigDecimal(oneOff.getAmount()).multiply(BigDecimal.valueOf(2)).compareTo(diff) >= 0;
  }

  static BigDecimal median(List<BiTransaction> rows) {
    if (rows.size() < 3) {
      return null;
    }
    List<BigDecimal> v = rows.stream().map(BiTransaction::getAmount).sorted().toList();
    int n = v.size();
    return n % 2 == 1 ? v.get(n / 2) : v.get(n / 2 - 1).add(v.get(n / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
  }

  private Driver categoryDriver(String kind, CategoryChange c, Map<UUID, BiCategory> cats) {
    return Driver.builder()
        .kind(kind)
        .category(category(c.id(), cats))
        .amount(mapper.money(sum(c.current())))
        .previousAmount(mapper.money(sum(c.previous())))
        .delta(mapper.money(c.diff()))
        .count((long) c.current().size())
        .previousCount((long) c.previous().size())
        .build();
  }

  private Tip limitTip(UUID userId, UUID categoryId, Map<UUID, BiCategory> cats, Window w, LocalDate today, Map<String, BiAskMrTip> rows) {
    String code = cats.get(categoryId) == null ? null : cats.get(categoryId).getCode();
    boolean hasLimit =
        budgetRows.findByUserIdAndPeriodStart(userId, YearMonth.from(today).atDay(1))
            .map(b -> limits.findAllByBudgetId(b.getId()).stream().map(BiBudgetCategoryLimit::getCategoryId).anyMatch(categoryId::equals))
            .orElse(false);
    Map<String, String> params = new LinkedHashMap<>();
    if (code != null) {
      params.put("category", code);
    }
    params.put("previousPeriod", YearMonth.from(w.prevFrom()).toString());
    return tip(rows, hasLimit ? "review-limit" : "set-limit", params);
  }

  private Map<String, BiAskMrTip> tipRows() {
    Map<String, BiAskMrTip> out = new HashMap<>();
    tips.findByIsActiveTrueOrderBySortOrderAscCodeAsc().forEach(t -> out.put(t.getCode(), t));
    return out;
  }

  private static Tip tip(Map<String, BiAskMrTip> rows, String id, Map<String, String> params) {
    BiAskMrTip row = rows.get(id);
    String[] seed = DEFAULT_TIPS.get(id);
    return Tip.builder()
        .id(id)
        .en(row != null ? row.getTextEn() : seed[0])
        .si(row != null ? row.getTextSi() : seed[1])
        .ta(row != null ? row.getTextTa() : seed[2])
        .params(params)
        .build();
  }

  private List<Note> notes(UUID userId, Scope sc, List<BiTransaction> cur, BigDecimal total, Window w, LocalDate today) {
    List<Note> out = new ArrayList<>();
    BigDecimal all = total == null ? sum(cur) : total;
    BigDecimal unsorted =
        cur.stream().filter(t -> t.getCategorySource() == CategorySource.FALLBACK).map(BiTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (unsorted.signum() > 0 && unsorted.multiply(HUNDRED).compareTo(all.multiply(BigDecimal.valueOf(UNCATEGORISED_NOTE_PCT))) > 0) {
      out.add(Note.builder().kind("UNCATEGORISED_HIGH").amount(mapper.money(unsorted)).build());
    }
    LocalDate latestSms = transactions.latestSmsRecordedOn(userId);
    if (latestSms != null && latestSms.isBefore(today.minusDays(SMS_GAP_DAYS))) {
      out.add(Note.builder().kind("SMS_GAP").since(latestSms).build());
    }
    long other = transactions.countOtherCurrencySpend(userId, w.from(), w.to(), sc.currency(), sc.all(), sc.ids());
    if (w.prevFrom() != null) {
      other += transactions.countOtherCurrencySpend(userId, w.prevFrom(), w.prevTo(), sc.currency(), sc.all(), sc.ids());
    }
    if (other > 0) {
      out.add(Note.builder().kind("OTHER_CURRENCY").count(other).build());
    }
    return out;
  }

  private AskMrAnswer.AskMrAnswerBuilder base(Question q, Window w) {
    return AskMrAnswer.builder()
        .question(q.name())
        .period(range(w.from(), w.to()))
        .comparePeriod(w.prevFrom() == null ? null : range(w.prevFrom(), w.prevTo()));
  }

  private AskMrAnswer tooEarly(Question q, Window w, LocalDate today) {
    AskMrAnswer.AskMrAnswerBuilder out = AskMrAnswer.builder().question(q.name()).status("TOO_EARLY");
    if (w != null) {
      out.period(range(w.from(), w.to())).comparePeriod(range(w.prevFrom(), w.prevTo()));
    } else {
      out.period(range(today, today));
    }
    return out.notes(List.of(Note.builder().kind("EARLY_MONTH").build())).build();
  }

  private Evidence transactionsEvidence(UUID categoryId, Map<UUID, BiCategory> cats, LocalDate from, LocalDate to, Scope sc) {
    return Evidence.builder()
        .kind("TRANSACTIONS")
        .path(WhatChangedServiceImpl.DRILLDOWN_PATH.replace("{categoryId}", categoryId.toString()))
        .category(category(categoryId, cats))
        .from(from)
        .to(to)
        .currency(sc.currency())
        .accountIds(sc.chosen())
        .build();
  }

  private static Evidence transactionEvidence(UUID transactionId) {
    return Evidence.builder().kind("TRANSACTION").transactionId(transactionId).build();
  }

  private List<BiTransaction> list(UUID userId, Scope sc, LocalDate from, LocalDate to) {
    if (from == null || to == null || to.isBefore(from)) {
      return List.of();
    }
    return transactions.listComparableSpend(userId, from, to, sc.currency(), sc.all(), sc.ids());
  }

  private static List<BiTransaction> only(UUID categoryId, List<BiTransaction> rows) {
    return rows.stream().filter(t -> categoryId.equals(t.getCategoryId())).toList();
  }

  private static Map<UUID, List<BiTransaction>> byCategory(List<BiTransaction> rows) {
    Map<UUID, List<BiTransaction>> out = new LinkedHashMap<>();
    for (BiTransaction t : rows) {
      if (t.getCategoryId() != null) {
        out.computeIfAbsent(t.getCategoryId(), k -> new ArrayList<>()).add(t);
      }
    }
    return out;
  }

  /** Grouped by merchant, ignoring case and spacing; rows without a merchant are left out. */
  private static Map<String, List<BiTransaction>> byMerchant(List<BiTransaction> rows) {
    Map<String, List<BiTransaction>> out = new LinkedHashMap<>();
    for (BiTransaction t : rows) {
      String m = t.getMerchant() == null ? "" : t.getMerchant().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
      if (!m.isEmpty()) {
        out.computeIfAbsent(m, k -> new ArrayList<>()).add(t);
      }
    }
    return out;
  }

  /** The merchant as most recently recorded. */
  private static String displayMerchant(List<BiTransaction> rows) {
    return rows.isEmpty() ? null : rows.get(rows.size() - 1).getMerchant().trim();
  }

  private Map<UUID, BiCategory> categoriesOf(List<BiTransaction> a, List<BiTransaction> b) {
    Set<UUID> ids = new HashSet<>();
    a.forEach(t -> ids.add(t.getCategoryId()));
    b.forEach(t -> ids.add(t.getCategoryId()));
    ids.remove(null);
    Map<UUID, BiCategory> out = new HashMap<>();
    categories.findAllById(ids).forEach(c -> out.put(c.getId(), c));
    return out;
  }

  private Map<UUID, BiUserBill> billsOf(List<BiTransaction> rows) {
    Set<UUID> ids = new HashSet<>();
    rows.forEach(t -> ids.add(t.getUserBillId()));
    ids.remove(null);
    Map<UUID, BiUserBill> out = new HashMap<>();
    if (!ids.isEmpty()) {
      bills.findAllById(ids).forEach(b -> out.put(b.getId(), b));
    }
    return out;
  }

  private static AskMrAnswer.Category category(UUID id, Map<UUID, BiCategory> cats) {
    BiCategory c = cats.get(id);
    return c == null ? AskMrAnswer.Category.builder().id(id).build() : toCategory(c);
  }

  private static AskMrAnswer.Category toCategory(BiCategory c) {
    return AskMrAnswer.Category.builder()
        .id(c.getId())
        .code(c.getCode())
        .name(c.getNameEn())
        .nameSi(c.getNameSi())
        .nameTa(c.getNameTa())
        .type(c.getSpendType())
        .build();
  }

  /** The category's type; not set behaves like FLEXIBLE. */
  static String typeOf(BiCategory c) {
    return c == null || c.getSpendType() == null ? BiCategory.TYPE_FLEXIBLE : c.getSpendType();
  }

  /** Health and education are never a place to suggest cutting back. */
  static boolean protectedEssential(BiCategory c) {
    return c != null && ("HEALTH_CARE".equals(c.getCode()) || "EDUCATION".equals(c.getCode()));
  }

  /**
   * Whether a "spend less here" suggestion (a limit, one fewer purchase) fits: flexible spending,
   * or an essential other than health and education. Never for bills or money moved.
   */
  static boolean cutBackTipFits(BiCategory c) {
    String type = typeOf(c);
    return BiCategory.TYPE_FLEXIBLE.equals(type) || (BiCategory.TYPE_ESSENTIAL.equals(type) && !protectedEssential(c));
  }

  private static BigDecimal sum(List<BiTransaction> rows) {
    return rows.stream().map(BiTransaction::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
  }

  private String average(List<BiTransaction> rows) {
    return rows.isEmpty() ? null : mapper.money(sum(rows).divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP));
  }

  static Double percent(BigDecimal diff, BigDecimal before) {
    return before.signum() <= 0 ? null : diff.multiply(HUNDRED).divide(before, 1, RoundingMode.HALF_UP).doubleValue();
  }

  private static Double share(BigDecimal part, BigDecimal total) {
    return total.signum() <= 0 ? null : part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP).doubleValue();
  }

  private static AskMrAnswer.Range range(LocalDate from, LocalDate to) {
    return AskMrAnswer.Range.builder().from(from).to(to).build();
  }

  private void count(String question, AskMrUsageRecorder.Event event) {
    try {
      usage.record(today(), question, event);
    } catch (RuntimeException e) {
      log.debug("Ask MR usage count not recorded: {}", e.getMessage());
    }
  }

  private LocalDate today() {
    return LocalDate.now(properties.zoneId());
  }
}
