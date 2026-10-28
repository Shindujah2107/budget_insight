package com.moneyreserve.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Profile screen: the customer's name and, optionally, email. An empty email removes it. */
public record AuthProfileUpdateRequest(
    @NotBlank(message = "Enter your full name.")
        @Size(min = 2, max = 120, message = "Enter your full name.")
        String fullName,
    @Email(message = "Check the email address, or leave it empty.")
        @Size(max = 254, message = "Check the email address, or leave it empty.")
        String email) {}
