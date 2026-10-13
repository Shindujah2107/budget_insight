package com.moipay.budgetinsights.domain;

/** Where a transaction came from. MANUAL covers cash spend and user-added rows. */
public enum TxnSource {
  SMS_ANDROID,
  SMS_IOS,
  MANUAL
}
