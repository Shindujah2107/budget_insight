package com.moipay.budgetinsights.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One day's count of money SMS from one sender that no template could read (Phase 1).
 *
 * <p>The annotations document the contract; the service checks each item itself, so one bad row
 * costs only its own slot (the reason {@code ClientComplianceServiceImpl} gives).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnmatchedSmsStat {

  @NotBlank(message = "Each count needs a sender.")
  @Pattern(
      regexp = "^[A-Za-z0-9 _+.-]{2,16}$",
      message = "The sender must be 2 to 16 letters, digits, space or _ + . -")
  private String senderId;

  @NotNull(message = "Each count needs a day.")
  private LocalDate day;

  @NotNull(message = "Each count needs a number.")
  private Integer count;
}
