package com.moneyreserve.service;

import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.dto.AccountDto;
import com.moneyreserve.dto.CategoryDto;
import com.moneyreserve.dto.CategoryTransactionsResponse;
import com.moneyreserve.dto.PaymentActivityResponse;
import com.moneyreserve.dto.SpendingBreakdownResponse;
import com.moneyreserve.service.calc.InsightsPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Everything the Insights screen reads.
 *
 * <p>Nothing here is stored as a displayed figure. The period total, each category's amount and its
 * share are computed from the transaction rows on every call, which is what lets a correction show
 * up immediately and what makes the aggregate cache safe to discard at any time.
 *
 * <p>Every method takes the owner's id, so there is no path through this service that can read
 * another customer's spending.
 */
public interface InsightsQueryService {

  SpendingBreakdownResponse breakdown(UUID userId, LocalDate from, LocalDate to);

  SpendingBreakdownResponse breakdown(UUID userId, InsightsPeriod period);

  CategoryTransactionsResponse categoryTransactions(
      UUID userId, UUID categoryId, LocalDate from, LocalDate to, int page, int size);

  /**
   * Daily spend for one week, split into the card and bank series the chart draws (BI-16).
   *
   * <p>Seven points per series, always, with {@code "0.00"} for a day with no spend. Both series
   * are returned even when {@code accountId} narrows to one instrument, because a chart that
   * changes shape when a filter is applied makes the client responsible for a distinction the
   * server already knows.
   *
   * @param weekStart the first day of the seven-day window; null means the week ending today, in
   *     the configured zone. This is a rolling window anchored on a date the client chooses, not a
   *     calendar week — the specification's own example starts on a Wednesday.
   * @param accountId one of the customer's accounts, or null for all of them
   */
  PaymentActivityResponse activity(UUID userId, LocalDate weekStart, UUID accountId);

  List<AccountDto> accounts(UUID userId);

  List<CategoryDto> categories();

  /** Resolves and validates a requested period, enforcing the approved look-back. */
  InsightsPeriod period(LocalDate from, LocalDate to);

  /** A category the customer may actually choose. Retired categories are not selectable. */
  BiCategory requireCategory(UUID categoryId);
}
