package com.moneyreserve.service.sms;

/** Sends one SMS. Implementations throw on failure so the caller can tell the customer. */
public interface SmsSender {

  /**
   * @param phone E.164 destination, e.g. {@code +94771234521}
   * @param text the whole message
   */
  void send(String phone, String text);
}
