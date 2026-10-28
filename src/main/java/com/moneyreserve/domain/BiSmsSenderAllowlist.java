package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Bank SMS senders eligible for processing.
 *
 * <p>This is a compliance control, not a convenience. A message from any sender not listed and
 * active here is ignored on the device before the OTP filter or the parser ever sees it. An empty
 * or fully inactive allowlist therefore means nothing is processed at all, which is the correct
 * default until the real sender identifiers are confirmed.
 */
@Entity
@Table(
    name = "bi_sms_sender_allowlist",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_sender_bank_sender",
          columnNames = {"bank_code", "sender_id"})
    },
    indexes = {@Index(name = "ix_bi_sender_active", columnList = "is_active, bank_code")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiSmsSenderAllowlist {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  /** Bank code (bi_bank). */
  @Column(name = "bank_code", nullable = false, length = 16)
  private String bankCode;

  @Column(name = "sender_id", nullable = false, length = 32)
  private String senderId;

  @Column(name = "country_code", nullable = false, length = 2)
  private String countryCode;

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
    this.createdAt = OffsetDateTime.now();
    if (this.isActive == null) {
      this.isActive = Boolean.TRUE;
    }
    if (this.countryCode == null) {
      this.countryCode = "LK";
    }
  }
}
