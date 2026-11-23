package com.moneyreserve.dto;

/**
 * An Ask MR suggestion's wording (V1.29.0) as published or drafted. {@code code} is fixed: the
 * rules choose tips by it, so only the wording and on/off change.
 */
public record AdminAskMrTipState(
    String code, String textEn, String textSi, String textTa, boolean active, int sortOrder) {

  public AdminAskMrTipState withActive(boolean value) {
    return new AdminAskMrTipState(code, textEn, textSi, textTa, value, sortOrder);
  }
}
