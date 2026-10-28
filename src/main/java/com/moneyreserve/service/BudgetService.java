package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.BudgetResponse;
import com.moneyreserve.dto.BudgetSummaryResponse;
import com.moneyreserve.dto.UpsertBudgetRequest;
import java.time.YearMonth;

/**
 * The monthly target: setting it, reading it back, and reporting spend against it.
 *
 * <p>Every method takes the {@link Caller}, and every query behind them is scoped to that caller's
 * id. Nothing here accepts a customer identifier, so there is no call shape that could read or
 * write another customer's budget.
 *
 * <p>Nothing is stored as a displayed figure. Spend, utilisation, pace and the alert states are all
 * derived from the transaction rows on each call, so a correction to a transaction changes the ring
 * on the next refresh with nothing to invalidate.
 */
public interface BudgetService {

  /** The budget card: ring, target, utilisation, pace and alerts — or the prompt to set one. */
  BudgetSummaryResponse summary(Caller caller, YearMonth month);

  /** The stored target and category caps for a period, for the set-a-budget screen. */
  BudgetResponse get(Caller caller, YearMonth month);

  /** Sets or replaces the target and category caps for a period. */
  BudgetResponse upsert(Caller caller, UpsertBudgetRequest request);

  /**
   * One attempt at {@link #upsert}, in a transaction of its own.
   *
   * <p>Not meant to be called from outside the implementation. It is on the interface because the
   * retry in {@code upsert} has to reach it through the Spring proxy: a transaction that has hit a
   * constraint violation is already doomed, so the second attempt must begin after the first has
   * rolled back, and an ordinary call on {@code this} would run inside the dead one.
   */
  BudgetResponse upsertOnce(Caller caller, UpsertBudgetRequest request);
}
