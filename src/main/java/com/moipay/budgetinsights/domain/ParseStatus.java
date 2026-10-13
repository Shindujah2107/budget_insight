package com.moipay.budgetinsights.domain;

/** Why a raw message was retained for parser improvement. Never set for a parsed message. */
public enum ParseStatus {
  UNPARSED,
  PARTIAL,
  FAILED
}
