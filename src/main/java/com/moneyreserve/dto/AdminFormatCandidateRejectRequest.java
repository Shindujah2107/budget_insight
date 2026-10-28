package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminFormatCandidateRejectRequest(
    @NotBlank
        @Pattern(
            regexp = "^(NOT_TRANSACTION|PROMOTION|DUPLICATE|OTHER)$",
            message = "reason must be NOT_TRANSACTION, PROMOTION, DUPLICATE or OTHER")
        String reason) {}
