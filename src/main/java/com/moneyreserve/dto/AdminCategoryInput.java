package com.moneyreserve.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Add or edit a spending category.
 *
 * <p>{@code spendBucket}: NEEDS, WANTS, SAVINGS, or NONE for "not counted in 50/30/20". Leaving it
 * out keeps the category's current bucket (a new category starts as not counted), so a client that
 * does not know the field yet cannot clear it by accident.
 */
public record AdminCategoryInput(
    @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$", message = "code must be UPPER_SNAKE, 2-32 characters")
        String code,
    @NotBlank @Size(max = 64) String nameEn,
    @Size(max = 64) String nameSi,
    @Size(max = 64) String nameTa,
    @NotBlank
        @Pattern(regexp = "^ic_[a-z0-9_]{1,45}$", message = "iconKey must look like ic_shopping")
        String iconKey,
    // Opaque only: the app draws charts and icon circles with it, where transparency would change
    // the colour against each background.
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colourHex must be #RRGGBB")
        String colourHex,
    @NotNull @Min(0) @Max(999) Integer sortOrder,
    @NotNull Boolean active,
    @Pattern(
            regexp = "^(NEEDS|WANTS|SAVINGS|NONE)$",
            message = "spendBucket must be NEEDS, WANTS, SAVINGS or NONE")
        String spendBucket,
    // Ask MR type (V1.30.0); NONE clears it, leaving it out keeps the current one.
    @Pattern(
            regexp = "^(BILL|ESSENTIAL|FLEXIBLE|EXCLUDED|NONE)$",
            message = "spendType must be BILL, ESSENTIAL, FLEXIBLE, EXCLUDED or NONE")
        String spendType) {

  /** Before V1.30.0 (no spending type: the current one is kept). */
  public AdminCategoryInput(
      String code,
      String nameEn,
      String nameSi,
      String nameTa,
      String iconKey,
      String colourHex,
      Integer sortOrder,
      Boolean active,
      String spendBucket) {
    this(code, nameEn, nameSi, nameTa, iconKey, colourHex, sortOrder, active, spendBucket, null);
  }
}
