package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;

/** Used for both refresh and log-out: the refresh token is the only thing either needs. */
public record AuthRefreshRequest(
    @NotBlank(message = "Your session has ended. Please log in again.") String refreshToken) {}
