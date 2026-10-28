package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Single-row revision counter of the admin changeset (V1.5.0), used for optimistic locking. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bi_config_changeset")
public class BiConfigChangeset {

  @Id
  @Column(name = "id", nullable = false)
  private Short id;

  @Column(name = "revision", nullable = false)
  private Long revision;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
