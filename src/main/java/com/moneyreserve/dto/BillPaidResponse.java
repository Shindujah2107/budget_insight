package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

/**
 * The outcome of marking a bill paid: the payment recorded (or the bank SMS it was paired with,
 * flagged as a duplicate), and the bill as it now stands. {@code alreadyPaid} when this cycle was
 * already paid, in which case nothing was recorded.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BillPaidResponse(Boolean alreadyPaid, TransactionMutationResponse payment, BillDto bill) {}
