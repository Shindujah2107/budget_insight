package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * First, deterministic pass of categorisation: map a parsed merchant string to a category.
 *
 * <p>Evaluated by ascending {@code priority}, then EXACT before PREFIX before REGEX. A customer's
 * own override always wins over anything matched here.
 */
@Entity
@Table(
    name = "bi_merchant_category_map",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_mcm_pattern_type",
          columnNames = {"merchant_pattern", "match_type"})
    },
    indexes = {@Index(name = "ix_bi_mcm_lookup", columnList = "is_active, priority, match_type")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiMerchantCategoryMap {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "merchant_pattern", nullable = false, length = 1000)
  private String merchantPattern;

  @Enumerated(EnumType.STRING)
  @Column(name = "match_type", nullable = false, length = 16)
  private MatchType matchType;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  @Column(name = "priority", nullable = false)
  private Short priority;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /**
   * The admin's form input for this rule ({@code matchType}, {@code names[]}, {@code
   * priorityLevel}), so it reopens in the simple form. NULL for seeded and advanced-only rules.
   * Categorisation never reads it: {@code merchantPattern} and {@code matchType} stay the rule.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rule_spec", columnDefinition = "jsonb")
  private String ruleSpec;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    this.createdAt = OffsetDateTime.now();
    if (this.isActive == null) {
      this.isActive = Boolean.TRUE;
    }
    if (this.priority == null) {
      this.priority = 100;
    }
  }
}
