package com.moipay.budgetinsights.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Success envelope.
 *
 * <p>Carried over verbatim from MoiPay-Backend rather than replaced with SplitEngine's bare-DTO
 * style, because the mobile client integrated against this shape during R0 and the brief for this
 * migration is that the R0/R1 contract does not change. The wire format is byte-identical to what
 * core returned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
  private boolean success;
  private String message;
  private T data;
  private List<T> items;
}
