package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One optional suggestion Ask MR can show under "What could help" (V1.29.0), in English, Sinhala
 * and Tamil, with {placeholders} ({category}, {merchant}, {amount}, {previousPeriod}) the app fills
 * in. Which tip applies is decided by rules in AskMrServiceImpl, by {@code code}; the wording and
 * on/off are edited in the Admin Dashboard (draft → publish). Configuration, no customer data.
 */
@Entity
@Table(
    name = "bi_ask_mr_tip",
    uniqueConstraints = {@UniqueConstraint(name = "ux_bi_ask_mr_tip_code", columnNames = {"code"})})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAskMrTip {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 40)
  private String code;

  @Column(name = "text_en", nullable = false, length = 400)
  private String textEn;

  @Column(name = "text_si", nullable = false, length = 400)
  private String textSi;

  @Column(name = "text_ta", nullable = false, length = 400)
  private String textTa;

  @Builder.Default
  @Column(name = "is_active", nullable = false)
  private Boolean isActive = Boolean.TRUE;

  @Builder.Default
  @Column(name = "sort_order", nullable = false)
  private Short sortOrder = 0;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
