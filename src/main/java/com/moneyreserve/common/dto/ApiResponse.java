package com.moneyreserve.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Success envelope.
 *
 * <p>{@code success}, an optional {@code message}, and the payload in {@code data} (or {@code
 * items} for lists). The app reads this shape on every route.
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
