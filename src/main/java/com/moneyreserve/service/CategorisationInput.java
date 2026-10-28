package com.moneyreserve.service;

import com.moneyreserve.domain.TxnDirection;

/**
 * What the categoriser is told about one transaction.
 *
 * <p>{@code messageType} and {@code direction} travel with the merchant so a category can be decided
 * by the kind of message (layer 0: an ATM receipt is cash whatever its location text says) and so a
 * future Cash/ATM or income category needs configuration, not a redesign.
 *
 * @param merchant the parsed merchant text, or null for cash and unparsed entries
 * @param messageType the parser template's message type ({@code ATM_RECEIPT}, {@code CARD_ALERT}…),
 *     or null (manual entries)
 * @param direction DEBIT or CREDIT, or null when unknown
 */
public record CategorisationInput(String merchant, String messageType, TxnDirection direction) {

  /** A merchant on its own: no message type, direction unknown. */
  public static CategorisationInput of(String merchant) {
    return new CategorisationInput(merchant, null, null);
  }
}
