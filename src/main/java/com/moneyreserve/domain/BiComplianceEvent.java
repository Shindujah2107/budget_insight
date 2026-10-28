package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * A compliance incident record. Append-only.
 *
 * <p>Compliance requires that a detected OTP or PIN capture is logged and treated as a formal
 * incident even though the message is discarded immediately and its value never stored or
 * transmitted. This entity is how that requirement is met, and its shape is the control: four
 * payload fields, no user foreign key, and deliberately no column capable of holding message
 * content or a financial value. Recording an incident cannot itself become a leak.
 *
 * <p>Do not add fields here without a Compliance review.
 */
@Entity
@Table(
    name = "bi_compliance_event",
    indexes = {@Index(name = "ix_bi_ce_type_time", columnList = "event_type, occurred_at")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiComplianceEvent {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private ComplianceEventType eventType;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 32)
  private ComplianceEventSource source;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 32)
  private ComplianceEventAction action;

  /** Salted hash. Lets repeated events be correlated without identifying the customer. */
  @Column(name = "device_id_hash", length = 64)
  private String deviceIdHash;

  /** Plain column, not a relation — this record intentionally holds no customer graph. */

  /**
   * A digest of the reporting client's reference, so a retried report is not a second incident.
   *
   * <p>Null on every event the server raises for itself; set only on the ones a handset reports
   * (BI-36). It is a <b>hash and never the value</b>, which is what lets a client-supplied string
   * exist on this table at all: a device cannot smuggle a code, a sender or a fragment of a message
   * into the one record here that is never purged, because what lands is sixty-four characters of
   * hex whatever it sent.
   *
   * <p>Uniqueness is enforced in PostgreSQL by {@code ux_bi_ce_client_ref}, a partial index
   * over the rows that have one. It is deliberately not declared here: this entity also generates
   * the H2 schema the integration tests run on, and a constraint declared in two places is a
   * constraint that can differ in two places.
   */
  @Column(name = "client_ref_hash", length = 64)
  private String clientRefHash;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.occurredAt == null) {
      this.occurredAt = OffsetDateTime.now();
    }
  }
}
