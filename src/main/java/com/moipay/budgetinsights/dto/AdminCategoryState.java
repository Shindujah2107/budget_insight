package com.moipay.budgetinsights.dto;

/**
 * A spending category's configurable values (a published row or a draft's desired state).
 *
 * @param spendBucket NEEDS, WANTS or SAVINGS; {@code null} when the category is not counted in
 *     50/30/20
 */
public record AdminCategoryState(
    String code,
    String nameEn,
    String nameSi,
    String nameTa,
    String iconKey,
    String colourHex,
    int sortOrder,
    boolean active,
    String spendBucket) {

  /** The fallback category; one constant with {@link com.moipay.budgetinsights.domain.BiCategory#OTHER_CODE}. */
  public static final String OTHER = com.moipay.budgetinsights.domain.BiCategory.OTHER_CODE;

  public AdminCategoryState withActive(boolean value) {
    return new AdminCategoryState(
        code, nameEn, nameSi, nameTa, iconKey, colourHex, sortOrder, value, spendBucket);
  }
}
