package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One SMS code sent for sign-in.
 *
 * <p>The code itself is never stored: {@code codeHash} is an HMAC of the code keyed by the signing
 * secret and bound to this row's id, so a copy of the table is useless without the key and one
 * row's hash says nothing about another's. A code is single-use and dies on the first of: being
 * used, expiring, running out of attempts, or a newer code being sent to the same number.
 */
@Entity
@Table(
    name = "bi_auth_otp",
    indexes = @Index(name = "ix_bi_auth_otp_phone_created", columnList = "phone, created_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAuthOtp {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "phone", nullable = false, length = 16)
  private String phone;

  @Enumerated(EnumType.STRING)
  @Column(name = "purpose", nullable = false, length = 16)
  private OtpPurpose purpose;

  @Column(name = "code_hash", nullable = false, length = 64)
  private String codeHash;

  @Column(name = "attempts", nullable = false)
  private int attempts;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  /** Set when the code is used, burnt by too many attempts, or replaced by a newer code. */
  @Column(name = "consumed_at")
  private OffsetDateTime consumedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.createdAt == null) {
      this.createdAt = OffsetDateTime.now();
    }
  }

  public boolean isOpen(OffsetDateTime now) {
    return consumedAt == null && expiresAt.isAfter(now);
  }
}
