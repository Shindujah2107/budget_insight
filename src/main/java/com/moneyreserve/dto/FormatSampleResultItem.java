package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** ACCEPTED, DUPLICATE (this user already counted for that format) or REJECTED with a code. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormatSampleResultItem {
  private String clientRef;
  private String status;
  private String error;
}
