package com.moneyreserve.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The shape of one SMS no template could read (Phase 2).
 *
 * <p>{@code tokens} is the only part that describes the message, and it cannot carry it: every
 * token must be a vocabulary id, a typed placeholder, a format in format letters, or one
 * punctuation character ({@code SkeletonGrammar}). The service checks each token; one bad token
 * rejects that sample only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormatSample {

  @NotBlank(message = "Each sample needs a client reference.")
  @Pattern(
      regexp = "^[A-Za-z0-9_-]{8,64}$",
      message = "The client reference must be 8 to 64 characters of letters, digits, _ or -.")
  private String clientRef;

  @NotBlank(message = "Each sample needs a sender.")
  @Pattern(
      regexp = "^[A-Za-z0-9 _+.-]{2,16}$",
      message = "The sender must be 2 to 16 letters, digits, space or _ + . -")
  private String senderId;

  @NotNull(message = "Each sample needs its tokens.")
  @Size(min = 4, max = 120, message = "A sample has 4 to 120 tokens.")
  private List<String> tokens;
}
