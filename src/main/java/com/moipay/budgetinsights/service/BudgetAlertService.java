package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.config.Caller;
import com.moipay.budgetinsights.dto.BudgetAlertDto;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Raises an alert when a budget threshold is crossed, exactly once per period (BI-25).
 *
 * <p>The evaluation itself already existed: the summary computes APPROACHING and EXCEEDED on every
 * read. What this adds is memory. "Exactly one in-app alert" is a claim about history, and a
 * service that only ever recomputes the present cannot make it.
 *
 * <p><b>No external message, ever.</b> §24 is explicit that crossing a limit raises an in-app alert
 * and nothing else. Nothing in this service sends SMS, push or email, and there is no code path
 * from here to one.
 */
public interface BudgetAlertService {

  /**
   * Evaluates the customer's caps for the period containing {@code onDate} and records any
   * threshold newly crossed.
   *
   * <p>Called after a write, inside the writer's transaction, so it sees the rows that caused the
   * crossing. Never throws into its caller: a failure to raise an alert must not turn a correctly
   * recorded transaction into a failed request.
   */
  void evaluate(Caller caller, LocalDate onDate);

  /** Alerts raised for the period containing {@code onDate}, newest first. */
  List<BudgetAlertDto> forPeriod(UUID userId, LocalDate onDate);

  /** Marks one alert as seen. Acknowledging twice is not an error. */
  BudgetAlertDto acknowledge(Caller caller, UUID alertId);
}
