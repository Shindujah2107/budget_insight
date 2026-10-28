package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Encrypted short-lived store for messages that failed to parse.
 *
 * <p>Written only on the consent-gated parser-improvement path and only for a message that could
 * not be parsed — never as part of normal ingest, and never for a message the OTP filter caught.
 * The ciphertext uses the platform's existing field codec, and rows are purged on {@link
 * #ttlExpiresAt} by the scheduled purge job.
 *
 * <p>This table exists so parser coverage can improve. It is not a copy of the customer's SMS inbox
 * and must not become one.
 */
@Entity
@Table(
    name = "bi_raw_message",
    indexes = {@Index(name = "ix_bi_raw_ttl", columnList = "ttl_expires_at")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiRawMessage {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;


  @Column(name = "bank_code", length = 16)
  private String bankCode;

  @Column(name = "ciphertext", nullable = false, columnDefinition = "text")
  private String ciphertext;

  @Enumerated(EnumType.STRING)
  @Column(name = "parse_status", nullable = false, length = 16)
  private ParseStatus parseStatus;

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
