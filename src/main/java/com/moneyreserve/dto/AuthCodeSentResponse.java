package com.moneyreserve.dto;

import com.moneyreserve.domain.OtpPurpose;

/**
 * A code is on its way.
 *
 * <p>{@code purpose} can differ from the screen that asked: logging in with a number that started
 * sign-up but never finished it sends a sign-up code, and the app shows the sign-up steps.
 */
public record AuthCodeSentResponse(
    String phone, OtpPurpose purpose, long expiresInSeconds, long resendAfterSeconds) {}
