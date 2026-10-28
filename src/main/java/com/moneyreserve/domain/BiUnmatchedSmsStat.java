package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Phase 1 of SMS format discovery: how many money SMS from one sender a handset could not read on
 * one day.
 *
 * <p>Counts only. There is no column that could hold a message, an amount or a user. One row per
 * hashed device per sender per day, so a handset that retries an upload replaces its count rather
 * than adding to it, and the coverage report can say how many devices saw a sender, not just how
 * many messages.
 */
@Entity
@Table(
    name = "bi_unmatched_sms_stat",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_unmatched_stat_day_sender_device",
          columnNames = {"stat_day", "sender_id", "device_hash"})
    },
    indexes = {@Index(name = "ix_bi_unmatched_stat_day", columnList = "stat_day")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiUnmatchedSmsStat {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "stat_day", nullable = false)
  private LocalDate statDay;

  @Column(name = "sender_id", nullable = false, length = 16)
  private String senderId;

  @Column(name = "device_hash", nullable = false, length = 64)
  private String deviceHash;

  @Column(name = "msg_count", nullable = false)
  private Integer msgCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
