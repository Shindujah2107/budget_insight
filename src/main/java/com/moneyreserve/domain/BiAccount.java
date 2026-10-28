package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * An account or card observed in the customer's bank SMS alerts.
 *
 * <p>These are not cards registered with MoneyReserve. They are whatever instruments appear in the messages
 * the customer's bank sends, identified by a three- or four-digit masked tail and nothing more. There is
 * deliberately no column here capable of holding a full card or account number: widening that would
 * require a schema change and therefore a documented Compliance approval.
 *
 * <p>{@link #instrumentType} is what splits the payment activity chart into its two series, so
 * every parser template must produce it.
 */
@Entity
@Table(
    name = "bi_account",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_account_user_bank_tail",
          columnNames = {"user_id", "bank_code", "instrument_type", "masked_tail"})
    },
    indexes = {@Index(name = "ix_bi_account_user", columnList = "user_id, is_active")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAccount {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;


  /** Bank code (bi_bank). Null where the bank is unrecognised. */
  @Column(name = "bank_code", length = 16)
  private String bankCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "instrument_type", nullable = false, length = 8)
  private InstrumentType instrumentType;

  /**
   * Last three or four digits only. Never a full PAN or account number.
   *
   * <p>Enforced by {@code ck_bi_account_masked_tail} (migration V1.0.3), not by the column width:
   * varchar(8) would accept eight characters of anything.
   */
  @Column(name = "masked_tail", nullable = false, length = 8)
  private String maskedTail;

  /** What the account filter shows, for example "Commercial 4821". */
  @Column(name = "display_name", length = 64)
  private String displayName;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

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
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
