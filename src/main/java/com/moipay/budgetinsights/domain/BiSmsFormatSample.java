package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * That one user sent one SMS format. Nothing else.
 *
 * <p>{@code userHash} is an HMAC of the user id under a server secret ({@code UserHasher}), used
 * only to count distinct users and to find this user's rows on consent withdrawal or erasure.
 * There is no user id, device id, tenant or message here. One row per user per candidate: a user
 * who keeps receiving the same unreadable SMS counts once.
 */
@Entity
@Table(
    name = "bi_sms_format_sample",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_sms_sample_candidate_user",
          columnNames = {"candidate_id", "user_hash"})
    },
    indexes = {
      @Index(name = "ix_bi_sms_sample_ttl", columnList = "ttl_expires_at"),
      @Index(name = "ix_bi_sms_sample_user", columnList = "user_hash")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiSmsFormatSample {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "candidate_id", nullable = false)
  private UUID candidateId;

  @Column(name = "user_hash", nullable = false, length = 64)
  private String userHash;

  @Column(name = "vocabulary_version", nullable = false)
  private Integer vocabularyVersion;

  @Column(name = "platform", length = 8)
  private String platform;

  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;

  @Column(name = "ttl_expires_at", nullable = false)
  private OffsetDateTime ttlExpiresAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.receivedAt == null) {
      this.receivedAt = OffsetDateTime.now();
    }
  }
}
