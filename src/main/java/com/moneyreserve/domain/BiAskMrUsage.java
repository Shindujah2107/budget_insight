package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import lombok.*;

/**
 * Ask MR usage counts per question per day (V1.29.0): answers shown, evidence opened, "Worth
 * knowing" cards dismissed. Counts only: no customer and no amounts.
 */
@Entity
@Table(name = "bi_ask_mr_usage")
@IdClass(BiAskMrUsage.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAskMrUsage {

  @Id
  @Column(name = "usage_day", nullable = false)
  private LocalDate day;

  @Id
  @Column(name = "question", nullable = false, length = 32)
  private String question;

  @Builder.Default
  @Column(name = "shown", nullable = false)
  private Integer shown = 0;

  @Builder.Default
  @Column(name = "evidence", nullable = false)
  private Integer evidence = 0;

  @Builder.Default
  @Column(name = "dismissed", nullable = false)
  private Integer dismissed = 0;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private LocalDate day;
    private String question;
  }
}
