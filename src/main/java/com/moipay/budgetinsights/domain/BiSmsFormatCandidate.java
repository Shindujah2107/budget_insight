package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

/**
 * One SMS format a template could not read, as a sender and a skeleton.
 *
 * <p>{@code tokenKey} and {@code displayText} hold only vocabulary words, placeholders, format
 * letters and punctuation: {@code SkeletonGrammar} checked every token before the row was written.
 * Nothing in this row came from a message other than its shape.
 *
 * <p><b>{@code @DynamicUpdate} is load-bearing.</b> Uploads, admin decisions, withdrawals and the
 * daily pass all touch these rows concurrently. An update writes only the columns it changed, so an
 * upload that touched {@code lastSeenAt} cannot write back a stale status or customer count it
 * read earlier. The counters themselves are only ever changed by bulk statements.
 */
@Entity
@Table(
    name = "bi_sms_format_candidate",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_sms_candidate_sender_hash",
          columnNames = {"sender_id", "format_hash"})
    },
    indexes = {
      @Index(name = "ix_bi_sms_candidate_status_users", columnList = "status, distinct_users")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@DynamicUpdate
public class BiSmsFormatCandidate {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "sender_id", nullable = false, length = 16)
  private String senderId;

  @Column(name = "format_hash", nullable = false, length = 64)
  private String formatHash;

  @Column(name = "token_key", nullable = false, length = 3000)
  private String tokenKey;

  @Column(name = "display_text", nullable = false, length = 3000)
  private String displayText;

  @Column(name = "token_count", nullable = false)
  private Integer tokenCount;

  /** Distinct users with a live sample. The admin list hides rows below the floor of 5. */
  @Column(name = "distinct_users", nullable = false)
  private Integer distinctUsers;

  /** Samples ever accepted, including expired ones; for the admin's sense of volume. */
  @Column(name = "sample_count", nullable = false)
  private Integer sampleCount;

  @Column(name = "first_seen_at", nullable = false)
  private OffsetDateTime firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private OffsetDateTime lastSeenAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 10)
  private FormatCandidateStatus status;

  @Column(name = "reject_reason", length = 20)
  private String rejectReason;

  @Column(name = "template_id")
  private UUID templateId;

  @Column(name = "decided_by")
  private UUID decidedBy;

  @Column(name = "decided_at")
  private OffsetDateTime decidedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.status == null) {
      this.status = FormatCandidateStatus.OPEN;
    }
    if (this.distinctUsers == null) {
      this.distinctUsers = 0;
    }
    if (this.sampleCount == null) {
      this.sampleCount = 0;
    }
  }
}
