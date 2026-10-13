package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * Where a device got to, so it can resume after being offline.
 *
 * <p>{@link #templateVersion} is what tells a device its parser templates are stale and it should
 * call reference sync before ingesting again.
 */
@Entity
@Table(
    name = "bi_device_sync_state",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_sync_user_device",
          columnNames = {"user_id", "device_id"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiDeviceSyncState {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "device_id", nullable = false, length = 128)
  private String deviceId;

  @Column(name = "last_synced_at")
  private OffsetDateTime lastSyncedAt;

  @Column(name = "last_ingest_seq", nullable = false)
  private Long lastIngestSeq;

  @Column(name = "template_version", nullable = false)
  private Integer templateVersion;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.lastIngestSeq == null) {
      this.lastIngestSeq = 0L;
    }
    if (this.templateVersion == null) {
      this.templateVersion = 0;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
