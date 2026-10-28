package com.moneyreserve.service;

import com.moneyreserve.domain.BiCategory;

/**
 * Decides which category a transaction belongs to.
 *
 * <p>One deterministic pass over the merchant map, then {@code OTHER}. There is no machine learning
 * here and none is planned for v1: a rule table a human can read, change and explain is worth more
 * on a screen that tells a customer where their money went than a model nobody can account for when
 * the customer disagrees.
 *
 * <p><b>What this service must never do</b> is overwrite a category the customer chose. It has no
 * access to the transaction at all — it takes a merchant string and returns a category — precisely
 * so that it cannot. The {@code isUserCategorised} latch is enforced by the caller, in
 * {@link InsightsTransactionService}.
 */
public interface CategorisationService {

  /**
   * Returns the category for a transaction. Never returns null.
   *
   * <p>Layer 0 decides by message type alone where configured ({@code
   * moneyreserve.insights.categorisation.message-type-categories}); otherwise the merchant rules
   * decide (layers 1–4), and {@code OTHER} is the fallback (layer 5).
   */
  BiCategory categorise(CategorisationInput input);

  /**
   * Returns the category for a merchant string alone (no message type, direction unknown).
   *
   * @param merchant the parsed merchant, or null for cash and unparsed entries
   */
  default BiCategory categorise(String merchant) {
    return categorise(CategorisationInput.of(merchant));
  }

  /** The {@code OTHER} category. Its absence is a broken deployment, not a runtime condition. */
  BiCategory fallback();

  /** Clears the compiled-pattern cache. Called after the merchant map is changed. */
  void refresh();
}
