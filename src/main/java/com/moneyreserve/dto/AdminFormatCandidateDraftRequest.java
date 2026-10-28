package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Which bank the template will belong to (usually the sender's bank). */
public record AdminFormatCandidateDraftRequest(@NotBlank @Size(max = 16) String bankCode) {}
