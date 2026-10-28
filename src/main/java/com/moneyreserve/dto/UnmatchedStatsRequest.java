package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/insights/formats/unmatched-stats. Counts only; there is no field for a message. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmatchedStatsRequest {

  @NotBlank(message = "The report needs a device identifier.")
  @Size(max = 128, message = "The device identifier is too long.")
  private String deviceId;

  @Pattern(regexp = "^(ANDROID|IOS)$", message = "The platform must be ANDROID or IOS.")
  private String platform;

  @NotEmpty(message = "The report needs at least one count.")
  private List<UnmatchedSmsStat> stats;
}
