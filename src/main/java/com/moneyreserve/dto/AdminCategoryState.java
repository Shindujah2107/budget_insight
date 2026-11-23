package com.moneyreserve.dto;

/**
 * A spending category's configurable values (a published row or a draft's desired state).
 *
 * @param spendBucket NEEDS, WANTS or SAVINGS; {@code null} when the category is not counted in
 *     50/30/20
 * @param spendType Ask MR type (V1.30.0): BILL, ESSENTIAL, FLEXIBLE or EXCLUDED; {@code null} when
 *     not set
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
    String spendBucket,
    String spendType) {

  /** The fallback category; one constant with {@link com.moneyreserve.domain.BiCategory#OTHER_CODE}. */
  public static final String OTHER = com.moneyreserve.domain.BiCategory.OTHER_CODE;

  /** Before V1.30.0 (no spending type). */
  public AdminCategoryState(
      String code,
      String nameEn,
      String nameSi,
      String nameTa,
      String iconKey,
      String colourHex,
      int sortOrder,
      boolean active,
      String spendBucket) {
    this(code, nameEn, nameSi, nameTa, iconKey, colourHex, sortOrder, active, spendBucket, null);
  }

  public AdminCategoryState withActive(boolean value) {
    return new AdminCategoryState(
        code, nameEn, nameSi, nameTa, iconKey, colourHex, sortOrder, value, spendBucket, spendType);
  }
}
