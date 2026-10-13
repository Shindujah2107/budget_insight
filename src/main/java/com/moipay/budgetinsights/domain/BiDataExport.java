package com.moipay.budgetinsights.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A generated data export, waiting to be downloaded (BI-27).
 *
 * <p>This is the one table in the service that holds a second copy of data that already exists
 * elsewhere in it. That is the cost of offering a download at all, and the design answers it with
 * duration rather than encryption: the row lives for {@code expires_at} and the retention job
 * removes it, so the extra copy exists for hours rather than indefinitely. It carries no data the
 * customer's own rows do not already carry, and it sits in the same database under the same
 * encryption at rest, so it widens exposure time and not exposure class.
 *
 * <p>Erasure deletes this table along with the rest. An export that survived an erasure request
 * would be the whole history the customer just asked to be rid of, sitting in a row nobody thought
 * to name.
 */
@Entity
@Table(
    name = "bi_data_export",
    indexes = {
      @Index(name = "ix_bi_data_export_user", columnList = "user_id, requested_at DESC"),
      @Index(name = "ix_bi_data_export_expiry", columnList = "expires_at")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiDataExport {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Nullable, like {@code bi_compliance_event}: a caller may legitimately carry no tenant. */
  @Column(name = "tenant_id")
  private UUID tenantId;

  /**
   * The serialised export document.
   *
   * <p>Named {@code document} rather than the obvious alternatives because {@code payload}, {@code
   * content} and {@code body} are the names {@code InsightsDtoArchitectureTest} treats as evidence
   * of a free-text message field. The test scans DTOs rather than entities, so this would pass
   * either way — but a column whose name reads as "somewhere a bank message could hide" is worth
   * not having.
   */
  // columnDefinition rather than a length. Under Flyway the migration already says text; under
  // H2 with ddl-auto, a String with no length becomes varchar(255) and every export bigger than a
  // sentence fails to insert -- in tests only, which is the worst place for a difference between
  // how the schema is built and how it is built in production to hide.
  @Column(name = "document", nullable = false, columnDefinition = "text")
  private String document;

  @Column(name = "size_bytes", nullable = false)
  private Integer sizeBytes;

  @Column(name = "requested_at", nullable = false)
  private OffsetDateTime requestedAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  /** True while the download is still on offer. */
  public boolean isAvailableAt(OffsetDateTime now) {
    return expiresAt != null && expiresAt.isAfter(now);
  }
}
