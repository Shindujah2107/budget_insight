package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Consent to read bank SMS, captured on a dedicated screen before the permission is requested and
 * separate from the general Terms and Conditions.
 *
 * <p>Append-only: a withdrawal writes a new row rather than mutating the grant, so the history of
 * what the customer agreed to and when survives. The current state is the latest row for the user.
 * Ingest checks it on every call, which is what makes withdrawal take effect on the next request
 * with no deploy.
 */
@Entity
@Table(
    name = "bi_consent",
    indexes = {@Index(name = "ix_bi_consent_user_latest", columnList = "user_id, created_at")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiConsent {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "tenant_id", nullable = false)
  private UUID tenantId;

  @Column(name = "consent_version", nullable = false, length = 16)
  private String consentVersion;

  /** Hash of the policy text actually shown, so what was agreed to can be proven later. */
  @Column(name = "policy_hash", nullable = false, length = 128)
  private String policyHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "channel", nullable = false, length = 16)
  private ConsentChannel channel;

  @Column(name = "granted_at")
  private OffsetDateTime grantedAt;

  @Column(name = "withdrawn_at")
  private OffsetDateTime withdrawnAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    this.createdAt = OffsetDateTime.now();
  }

  /** True when this row represents consent currently in force. */
  public boolean isActive() {
    return grantedAt != null && withdrawnAt == null;
  }
}
