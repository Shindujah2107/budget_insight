package com.moneyreserve.dto;

/** A bank SMS sender allowlist row. The sender id is case-sensitive, as the handset matches it. */
public record AdminSenderState(String bankCode, String senderId, String countryCode, boolean active) {

  public AdminSenderState withActive(boolean value) {
    return new AdminSenderState(bankCode, senderId, countryCode, value);
  }
}
