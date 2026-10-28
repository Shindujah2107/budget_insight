package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One result per sample; every result is final, so the device deletes every sample listed. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormatSampleBatchResponse {
  private int accepted;
  private int duplicates;
  private int rejected;
  private List<FormatSampleResultItem> results;
}
