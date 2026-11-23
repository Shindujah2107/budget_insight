package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One admin "Re-sort old transactions" run (V1.31.0): who applied it, when, how many payments
 * moved, and whether it was undone. Staff data: no customer id.
 */
@Entity
@Table(name = "bi_recategorise_run")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiRecategoriseRun {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Builder.Default
  @Column(name = "moved", nullable = false)
  private Integer moved = 0;

  @Column(name = "undone_at")
  private OffsetDateTime undoneAt;

  @Column(name = "undone_by")
  private UUID undoneBy;
}
