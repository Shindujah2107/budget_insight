package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One of the 17 spending categories.
 *
 * <p>{@code name} is already resolved to the caller's language by the backend, so the client never
 * has to choose between the English, Sinhala and Tamil columns. Where a translation is missing the
 * English name is returned rather than a blank.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryDto {
  private UUID categoryId;
  private String code;
  private String name;
  private String iconKey;
  private Integer sortOrder;
}
