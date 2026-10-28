package com.moneyreserve.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create-account screen: sends a sign-up code to {@code phone}. */
public record AuthRegisterRequest(
    @NotBlank(message = "Enter your full name.")
        @Size(min = 2, max = 120, message = "Enter your full name.")
        String fullName,
    @NotBlank(message = "Enter your mobile number.") String phone,
    @Email(message = "Check the email address, or leave it empty.")
        @Size(max = 254, message = "Check the email address, or leave it empty.")
        String email) {}
