package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One customer's "always use this category for this merchant" choice.
 *
 * <p>Personal, never shared: it changes only this customer's rows, and it is consulted before the
 * global merchant rules on their next bank alerts. The key is the merchant as {@code
 * MerchantRuleMatcher.normalise} reads it, so "Pizza Hut" and "PIZZA  HUT" are one choice. Erased and
 * exported with the rest of the customer's data.
 */
@Entity
@Table(
    name = "bi_user_merchant_category",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_umc_user_merchant",
          columnNames = {"user_id", "merchant_key"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiUserMerchantCategory {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "merchant_key", nullable = false, length = 128)
  private String merchantKey;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
