package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One published reference release (V1.5.0).
 *
 * <p>{@code MAX(version)} is the reference version handsets sync against. Written only by the admin
 * publish (and the Flyway seed row); never updated or deleted, so the history is complete. Mapped
 * here so the reference-version query and local H2 schemas see the table; the admin code reads and
 * writes it with plain SQL.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "bi_reference_release")
public class BiReferenceRelease {

  @Id
  @Column(name = "version", nullable = false)
  private Integer version;

  @Column(name = "published_at", nullable = false)
  private OffsetDateTime publishedAt;

  @Column(name = "published_by")
  private UUID publishedBy;

  @Column(name = "published_by_role", nullable = false, length = 32)
  private String publishedByRole;

  @Column(name = "reason", nullable = false, length = 500)
  private String reason;

  @Column(name = "change_count", nullable = false)
  private Integer changeCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "summary", nullable = false, columnDefinition = "jsonb")
  private String summary;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "diff", nullable = false, columnDefinition = "jsonb")
  private String diff;
}
