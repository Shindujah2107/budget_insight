package com.moneyreserve.dto;

import com.moneyreserve.domain.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Enter-the-code screen. {@code purpose} is the one the code-sent response named. */
public record AuthVerifyRequest(
    @NotBlank(message = "Enter your mobile number.") String phone,
    @NotBlank(message = "Enter the 6-digit code.")
        @Pattern(regexp = "^\\d{4,8}$", message = "Enter the 6-digit code.")
        String code,
    @NotNull(message = "Something went wrong. Please try again.") OtpPurpose purpose) {}
