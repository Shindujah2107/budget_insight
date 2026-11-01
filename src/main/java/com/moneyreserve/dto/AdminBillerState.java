package com.moneyreserve.dto;

import java.util.List;

/**
 * A biller's configurable values (a published row or a draft's desired state).
 *
 * @param terms name keywords, written as merchant rules write them ({@code DIALOG}, {@code CEYLON
 *     ELECTRICITY}, {@code -REFUND})
 * @param tolerancePct ±% one payment may differ from the usual; {@code null} uses the bill type's
 */
public record AdminBillerState(
    String code,
    String nameEn,
    String nameSi,
    String nameTa,
    String billType,
    List<String> terms,
    String iconKey,
    String colourHex,
    Integer tolerancePct,
    int sortOrder,
    boolean active) {

  public AdminBillerState withActive(boolean value) {
    return new AdminBillerState(
        code, nameEn, nameSi, nameTa, billType, terms, iconKey, colourHex, tolerancePct, sortOrder, value);
  }
}
