package com.moipay.budgetinsights.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** POST /api/insights/formats/samples. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatSampleBatchRequest {

  @NotBlank(message = "The report needs a device identifier.")
  @Size(max = 128, message = "The device identifier is too long.")
  private String deviceId;

  @Pattern(regexp = "^(ANDROID|IOS)$", message = "The platform must be ANDROID or IOS.")
  private String platform;

  /** The vocabulary version the phone used: word ids are checked against that version. */
  @NotNull(message = "The report needs the vocabulary version.")
  @Min(value = 1, message = "The vocabulary version is not valid.")
  private Integer vocabularyVersion;

  @NotEmpty(message = "The report needs at least one sample.")
  private List<FormatSample> samples;
}
