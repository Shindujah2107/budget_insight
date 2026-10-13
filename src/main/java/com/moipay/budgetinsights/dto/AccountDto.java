package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An account shown in the All Accounts filter.
 *
 * <p>Carries a four-digit masked tail and a display label, and nothing else. There is no field here
 * for a fuller identifier because none exists anywhere in the feature.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountDto {
  private UUID accountId;
  private String bankCode;
  private String instrumentType;
  private String maskedTail;
  private String label;
}
