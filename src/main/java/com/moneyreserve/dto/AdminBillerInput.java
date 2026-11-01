package com.moneyreserve.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Create or edit a biller (Spending → Billers). */
public record AdminBillerInput(
    @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,31}$", message = "code must be UPPER_SNAKE, 2-32 characters")
        String code,
    @NotBlank @Size(max = 64) String nameEn,
    @Size(max = 64) String nameSi,
    @Size(max = 64) String nameTa,
    @NotBlank
        @Pattern(
            regexp = "^(ELECTRICITY|WATER|TELECOM|STREAMING|INSURANCE|LOAN|CARD|RENT|OTHER)$",
            message = "billType must be one of the bill types")
        String billType,
    @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 60) String> terms,
    @NotBlank
        @Pattern(regexp = "^ic_[a-z0-9_]{1,45}$", message = "iconKey must look like ic_utilities")
        String iconKey,
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "colourHex must be #RRGGBB") String colourHex,
    @Min(0) @Max(200) Integer tolerancePct,
    @NotNull @Min(0) @Max(999) Integer sortOrder,
    @NotNull Boolean active) {}
