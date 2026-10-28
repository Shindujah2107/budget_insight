package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 50/30/20 guidance, measured against the customer's own target and their own history (BI-26).
 *
 * <p><b>Read the percentages carefully: they are shares of the monthly target, not of income.</b>
 * Budget Insights never sees income — it sees money leaving bank accounts. Splitting the target is
 * what makes the third bucket mean anything: the 20% is money deliberately left unspent, which is
 * observable as target minus spend. A savings figure assembled from outgoing categories instead
 * would tell a customer who saves by standing order that they save nothing, which is worse than
 * saying nothing at all.
 *
 * <p>The suggested caps are each category's own trailing median, so they are a description of how
 * this customer actually lives rather than a rule imported from somewhere else. §14: "suggested
 * caps from the trailing three-month median, taken only from within the six-month window."
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecommendationsResponse {

  private PeriodDto period;
  private String currency;

  /**
   * The figure the three buckets are shares of.
   *
   * <p>The customer's monthly target when they have set one. When they have not, their trailing
   * median spend, which is the same basis BI-24 uses to suggest a first target.
   */
  private String basisAmount;

  /** Plain words for where {@link #basisAmount} came from, for the client to print. */
  private String basis;

  /** Months the medians are taken over (the budget policy's recommendation period). */
  private Integer periodMonths;

  /** True when the basis is a target the customer set rather than one inferred from history. */
  private boolean targetSet;

  private List<Bucket> buckets;

  /**
   * Spend in the trailing window that falls in no bucket.
   *
   * <p>{@code OTHER} and {@code TRANSFERS}, by design. Reported rather than hidden so the bucket
   * amounts and this add up to the trailing total — a panel whose parts do not sum to the whole
   * reads as a bug even when every part is right.
   */
  private String unbucketedAmount;

  /** Absent when nothing is unbucketed. */
  private List<CategorySuggestion> unbucketed;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Bucket {

    /** NEEDS, WANTS or SAVINGS. */
    private String key;

    private String label;

    /** The guidance: 50, 30 or 20. */
    private int targetPct;

    /** {@code targetPct} of the basis — what this bucket should cost at most. */
    private String targetAmount;

    /**
     * What the customer actually spends, as the trailing median.
     *
     * <p>For SAVINGS this is the basis minus everything spent, plus the spend in any category
     * mapped to savings — debt repayment belongs in the 20% alongside money set aside, so it is
     * credited rather than charged twice. It can be negative: a customer who spends more than
     * their target is saving less than nothing, and rounding that up to zero would be flattering
     * them with their own money.
     */
    private String actualAmount;

    /** {@code actualAmount} as a share of the basis, one decimal place. */
    private Double actualPct;

    /** OVER when the bucket exceeds its share, UNDER when short of it, ON otherwise. */
    private String state;

    /** Per-category suggested caps. Absent on a bucket no category maps to. */
    private List<CategorySuggestion> categories;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CategorySuggestion {
    private String categoryCode;
    private String categoryName;

    /** The trailing median for this category — the suggested cap. */
    private String suggestedCap;
  }
}
