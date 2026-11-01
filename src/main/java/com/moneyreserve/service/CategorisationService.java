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
  default BiCategory categorise(CategorisationInput input) {
    return decide(input).category();
  }

  /**
   * The category and why: {@code RULE} when the message type or a merchant rule decided (even when
   * it decided OTHER), {@code FALLBACK} when nothing matched and OTHER is only the default.
   */
  Decision decide(CategorisationInput input);

  /**
   * As {@link #decide(CategorisationInput)}, but this customer's own "always use this category for
   * this merchant" choice wins first (source {@code USER}). Never consults another customer's.
   */
  default Decision decide(java.util.UUID userId, CategorisationInput input) {
    return decide(input);
  }

  /**
   * Remembers this customer's category for a merchant, for their future rows. Returns the merchant
   * key it was stored under, or null when the merchant has nothing to match on.
   */
  default String remember(java.util.UUID userId, String merchant, java.util.UUID categoryId) {
    return null;
  }

  /** A category and the reason it was chosen. */
  record Decision(BiCategory category, com.moneyreserve.domain.CategorySource source) {}

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
