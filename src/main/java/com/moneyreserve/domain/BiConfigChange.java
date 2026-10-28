package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A pending (unpublished) admin change to one reference item (V1.5.0).
 *
 * <p>At most one per item: editing the same item again replaces the change. {@code payload} is the
 * desired state, {@code beforeState} the published row when the draft was made. Neither ever holds
 * sample SMS text.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "bi_config_change",
    uniqueConstraints =
        @UniqueConstraint(name = "ux_bi_cc_target", columnNames = {"entity_type", "target_id"}))
public class BiConfigChange {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 24)
  private ConfigEntityType entityType;

  @Column(name = "target_id", nullable = false)
  private UUID targetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "op", nullable = false, length = 12)
  private ConfigChangeOp op;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  private String payload;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "before_state", columnDefinition = "jsonb")
  private String beforeState;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_by", nullable = false)
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
