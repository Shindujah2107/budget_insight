package com.moneyreserve.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * "Yes, this is my … payment" (link the bank SMS to the payment recorded for the bill), or Undo
 * (only {@code smsTransactionId}: the SMS goes back to being its own transaction).
 */
public record BillSmsMatchRequest(@NotNull UUID smsTransactionId, UUID recordedTransactionId) {}
