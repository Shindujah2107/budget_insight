package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Cached aggregate for a customer and period.
 *
 * <p>Always reconstructible from {@link BiTransaction}, so it is safe to delete at any time. Every
 * write to a transaction deletes the cache rows whose period overlaps that transaction's date,
 * inside the same database transaction — which is what makes a re-categorisation show up
 * immediately rather than on the next refresh.
 */
@Entity
@Table(
    name = "bi_insight_cache",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_cache_user_key_period",
          columnNames = {"user_id", "cache_key", "period_start", "period_end"})
    },
    indexes = {
      @Index(name = "ix_bi_cache_user_period", columnList = "user_id, period_start, period_end")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiInsightCache {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "cache_key", nullable = false, length = 64)
  private String cacheKey;

  @Column(name = "period_start", nullable = false)
  private LocalDate periodStart;

  @Column(name = "period_end", nullable = false)
  private LocalDate periodEnd;

  // Required, not decoration: without it Hibernate binds via setString and PostgreSQL
  // rejects the insert with 42804 (column is jsonb, expression is character varying).
  // Matches DserEventLog.payload, BillPaymentLog and AccountClosureRequest.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "computed_at", nullable = false)
  private OffsetDateTime computedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.computedAt == null) {
      this.computedAt = OffsetDateTime.now();
    }
  }
}
