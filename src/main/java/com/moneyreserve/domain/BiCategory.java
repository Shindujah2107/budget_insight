package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One of the 17 spending categories confirmed under CR-BUDGET-INSIGHTS-01.
 *
 * <p>Reference data, not user data. Seeded in Flyway V2.19.1 with fixed ids so a category means the
 * same thing in every environment. Adding, renaming or re-ordering a category is a data change and
 * does not require an app release.
 */
@Entity
@Table(
    name = "bi_category",
    uniqueConstraints = {@UniqueConstraint(name = "ux_bi_category_code", columnNames = "code")},
    indexes = {@Index(name = "ix_bi_category_active", columnList = "is_active, sort_order")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiCategory {

  /** Fallback category code. Must always exist and stay active — see {@code isFallback()}. */
  public static final String OTHER_CODE = "OTHER";

  /** Cash taken out at an ATM or branch: listed, but never counted as spending. */
  public static final String CASH_WITHDRAWAL_CODE = "CASH_WITHDRAWAL";

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 32)
  private String code;

  @Column(name = "name_en", nullable = false, length = 64)
  private String nameEn;

  @Column(name = "name_si", length = 64)
  private String nameSi;

  @Column(name = "name_ta", length = 64)
  private String nameTa;

  /** Icon the mobile client renders beside the category name. */
  @Column(name = "icon_key", nullable = false, length = 48)
  private String iconKey;

  /**
   * Display colour ({@code #RRGGBB}) sent to the app with the category. Seeded with the app's
   * palette by V1.18.0 and edited in the Admin Dashboard; the app falls back to its own colour
   * when this is empty.
   */
  @Column(name = "colour_hex", length = 9)
  private String colourHex;

  /**
   * 50/30/20 bucket: NEEDS, WANTS or SAVINGS; null means "not counted" (reported as unbucketed).
   * Edited in the Admin Dashboard since V1.8.0.
   */
  @Column(name = "spend_bucket", length = 8)
  private String spendBucket;

  /**
   * Ask MR spending type (V1.30.0): BILL, ESSENTIAL, FLEXIBLE or EXCLUDED; null means not set
   * (treated like FLEXIBLE, without a label). Edited in the Admin Dashboard.
   */
  @Column(name = "spend_type", length = 10)
  private String spendType;

  public static final String TYPE_BILL = "BILL";
  public static final String TYPE_ESSENTIAL = "ESSENTIAL";
  public static final String TYPE_FLEXIBLE = "FLEXIBLE";
  public static final String TYPE_EXCLUDED = "EXCLUDED";

  @Column(name = "sort_order", nullable = false)
  private Short sortOrder;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.isActive == null) {
      this.isActive = Boolean.TRUE;
    }
    if (this.sortOrder == null) {
      this.sortOrder = 0;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }

  public boolean isFallback() {
    return OTHER_CODE.equals(code);
  }
}
