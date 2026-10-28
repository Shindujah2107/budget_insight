package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;

/** Log-in screen: sends a log-in code to {@code phone}. */
public record AuthLoginRequest(@NotBlank(message = "Enter your mobile number.") String phone) {}
