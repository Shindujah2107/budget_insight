package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * A "Worth knowing" card (Ask MR, V1.29.0) shown to a customer: the topic and the day, so at most
 * N are shown a week and a topic never repeats within 14 days. No amounts. Personal: exported and
 * erased with the rest of the customer's data.
 */
@Entity
@Table(
    name = "bi_ask_mr_worth",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_ask_mr_worth_day",
          columnNames = {"user_id", "topic", "shown_on"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAskMrWorth {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** e.g. {@code CATEGORY:DINING_OUT:UP}. */
  @Column(name = "topic", nullable = false, length = 80)
  private String topic;

  @Column(name = "shown_on", nullable = false)
  private LocalDate shownOn;

  @Column(name = "dismissed_at")
  private OffsetDateTime dismissedAt;
}
