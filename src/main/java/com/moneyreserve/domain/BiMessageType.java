package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A message type SMS templates pick from (Configuration › Message types, V1.15.0).
 *
 * <p>The code is what a template, a handset and a transaction carry; it never changes after
 * creation and is referenced as text, not as a foreign key. The logical type is the cross-bank
 * group new templates of this type take. Direction and instrument are only the template wizard's
 * defaults. A fixed category makes categorisation decide every SMS of this type by type alone (see
 * {@code MessageTypeCategories}). Changes apply immediately: a message type on its own reaches no
 * handset.
 */
@Entity
@Table(name = "bi_message_type")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiMessageType {

  @Id
  @Column(name = "code", nullable = false, length = 32)
  private String code;

  @Column(name = "display_name", nullable = false, length = 64)
  private String displayName;

  @Column(name = "logical_type", nullable = false, length = 48)
  private String logicalType;

  /** {@code FROM_SMS}, {@code DEBIT} or {@code CREDIT}. */
  @Column(name = "default_direction", nullable = false, length = 8)
  private String defaultDirection;

  /** {@code BANK} or {@code CARD}. */
  @Column(name = "default_instrument", nullable = false, length = 8)
  private String defaultInstrument;

  /** Category code every SMS of this type gets without merchant rules; null = merchant rules. */
  @Column(name = "fixed_category_code", length = 32)
  private String fixedCategoryCode;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Staff id of the Super Admin who added it; null for types seeded by the migration. */
  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Column(name = "updated_by")
  private UUID updatedBy;
}
