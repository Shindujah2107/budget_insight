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

  /**
   * Display colour as {@code #RRGGBB}, set in the Admin Dashboard (seeded with the app's palette by
   * V1.18.0). Omitted when unset; the app then uses its own colour for the code.
   */
  private String colourHex;

  /** Sinhala and Tamil names when the Admin Dashboard has them; the app falls back to {@code name}. */
  private String nameSi;

  private String nameTa;

  /**
   * False for a category turned off in the Admin Dashboard. Only listed with {@code
   * includeInactive=true}, so the app can still name it on older transactions.
   */
  private Boolean active;

  /** 50/30/20 bucket: NEEDS, WANTS or SAVINGS; absent when not counted. */
  private String spendBucket;
}
