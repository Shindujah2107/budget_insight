package com.moneyreserve.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Every count in the request was either recorded or rejected; the device clears them all. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmatchedStatsResponse {
  private int recorded;
  private int rejected;
}
