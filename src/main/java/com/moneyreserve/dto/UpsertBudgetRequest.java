package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sets the monthly target for one period, with optional per-category caps.
 *
 * <p>Replace semantics, not patch: the category limits sent are the category limits the period
 * ends up with, and sending none clears them. A customer removing a cap does so by saving the
 * screen without it, and patch semantics would give no way to express that.
 *
 * <p>Amounts arrive as JSON numbers here rather than the decimal strings the responses use. Jackson
 * binds a JSON number straight to {@link BigDecimal} without going through a double, so the
 * precision problem the responses avoid does not arise on the way in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpsertBudgetRequest {

  /** The period, as {@code YYYY-MM}. */
  @NotBlank(message = "Please choose a budget period.")
  @Pattern(regexp = "\\d{4}-\\d{2}", message = "Please choose a valid budget period.")
  private String period;

  /**
   * The target for the period.
   *
   * <p>Optional only when {@code rollForward} is set, in which case the previous period's target is
   * carried over. Absent in any other case is a refusal, not a budget of zero.
   */
  @DecimalMin(value = "0.01", message = "Please enter a budget amount greater than zero.")
  @Digits(integer = 17, fraction = 2, message = "Please enter a valid budget amount.")
  private BigDecimal overallLimit;

  /** Defaults to the deployment's currency. */
  @Size(min = 3, max = 3, message = "Please use a three-letter currency code.")
  private String currency;

  @Valid private List<CategoryLimit> categoryLimits;

  /**
   * Carry the most recent earlier target into this period.
   *
   * <p>With no {@code overallLimit} the previous target, currency and category caps are copied.
   * With one, the given target is used and the carry-forward is recorded as provenance only — the
   * customer took last month's budget as a starting point and then changed it, and the row says so.
   */
  private Boolean rollForward;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class CategoryLimit {

    /** The category's stable code, for example {@code DINING_OUT}. */
    @NotBlank(message = "Please choose a category from the list.")
    private String categoryCode;

    @NotNull(message = "Please enter a category limit greater than zero.")
    @DecimalMin(value = "0.01", message = "Please enter a category limit greater than zero.")
    @Digits(integer = 17, fraction = 2, message = "Please enter a valid category limit.")
    private BigDecimal limitAmount;

    /** Percentage of the cap at which the approaching-limit alert fires. Defaults to 80. */
    @Min(value = 1, message = "The alert threshold must be between 1 and 100.")
    @Max(value = 100, message = "The alert threshold must be between 1 and 100.")
    private Integer alertThresholdPct;
  }
}
