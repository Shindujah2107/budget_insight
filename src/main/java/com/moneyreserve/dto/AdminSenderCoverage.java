package com.moneyreserve.dto;

import java.time.LocalDate;

/**
 * One sender on the SMS coverage page: how many money SMS from it handsets could not read.
 *
 * @param onAllowlist whether phones are told to read this sender at all
 * @param liveTemplates published templates for the sender's bank (0 when the bank is unknown)
 */
public record AdminSenderCoverage(
    String senderId,
    String bankCode,
    String bankName,
    long unmatched,
    long devices,
    LocalDate lastSeen,
    boolean onAllowlist,
    int liveTemplates) {}
