package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.UUID;
import lombok.*;

/**
 * One payment moved by a "Re-sort old transactions" run (V1.31.0): the category it had and how it
 * got it, so the run can be undone. Customer data (erased with the customer); no merchant text or
 * amount.
 */
@Entity
@Table(name = "bi_recategorise_move")
@IdClass(BiRecategoriseMove.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiRecategoriseMove {

  @Id
  @Column(name = "run_id", nullable = false)
  private UUID runId;

  @Id
  @Column(name = "transaction_id", nullable = false)
  private UUID transactionId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "from_category_id", nullable = false)
  private UUID fromCategoryId;

  @Column(name = "to_category_id", nullable = false)
  private UUID toCategoryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_source", length = 10)
  private CategorySource fromSource;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private UUID runId;
    private UUID transactionId;
  }
}
