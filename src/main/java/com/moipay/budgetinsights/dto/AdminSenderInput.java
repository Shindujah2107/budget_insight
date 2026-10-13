package com.moipay.budgetinsights.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Add or edit an SMS sender. The sender id is case-sensitive.
 *
 * <p>Send either {@code bankCode} (an existing bank) or, on create only, {@code newBank} to add
 * the bank together with the sender. A bank added that way is "pending" until the sender is
 * published, and is removed again if the sender draft is discarded.
 */
public record AdminSenderInput(
    @Size(max = 16) String bankCode,
    @NotBlank
        @Size(max = 32)
        @Pattern(regexp = "^[A-Za-z0-9_+.\\- ]+$", message = "senderId has characters a sender id cannot have")
        String senderId,
    @Pattern(regexp = "^[A-Z]{2}$", message = "countryCode must be ISO-3166 alpha-2") String countryCode,
    @NotNull Boolean active,
    @Valid AdminBankInput newBank) {}
