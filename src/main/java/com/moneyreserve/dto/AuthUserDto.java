package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moneyreserve.domain.AppRole;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthUserDto(
    UUID id,
    String fullName,
    String phone,
    String email,
    AppRole role,
    OffsetDateTime verifiedAt) {}
