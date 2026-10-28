package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A refresh token, stored only as a SHA-256 hash.
 *
 * <p>Tokens rotate: each refresh revokes the token used and issues a new one in the same {@code
 * familyId}. Presenting a token that was already rotated means two parties hold the same login —
 * the token has been copied — so the whole family is revoked and both must sign in again.
 */
@Entity
@Table(
    name = "bi_auth_refresh_token",
    uniqueConstraints =
        @UniqueConstraint(name = "uq_bi_auth_refresh_token_hash", columnNames = "token_hash"),
    indexes = {
      @Index(name = "ix_bi_auth_refresh_token_family", columnList = "family_id"),
      @Index(name = "ix_bi_auth_refresh_token_user", columnList = "user_id")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiRefreshToken {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "revoked_at")
  private OffsetDateTime revokedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    this.createdAt = OffsetDateTime.now();
  }
}
