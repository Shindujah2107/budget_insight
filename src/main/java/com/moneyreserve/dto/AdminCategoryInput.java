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
    @Pattern(
            regexp = "^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$",
            message = "colourHex must be #RRGGBB or #RRGGBBAA")
        String colourHex,
    @NotNull @Min(0) @Max(999) Integer sortOrder,
    @NotNull Boolean active,
    @Pattern(
            regexp = "^(NEEDS|WANTS|SAVINGS|NONE)$",
            message = "spendBucket must be NEEDS, WANTS, SAVINGS or NONE")
        String spendBucket) {}
