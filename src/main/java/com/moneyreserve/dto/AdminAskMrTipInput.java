package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Draft new wording for an Ask MR suggestion: all three languages, and whether it is shown. */
public record AdminAskMrTipInput(
    @NotBlank @Size(max = 400) String textEn,
    @NotBlank @Size(max = 400) String textSi,
    @NotBlank @Size(max = 400) String textTa,
    @NotNull Boolean active) {}
