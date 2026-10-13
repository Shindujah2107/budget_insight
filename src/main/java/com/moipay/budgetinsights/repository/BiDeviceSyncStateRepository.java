package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiDeviceSyncState;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiDeviceSyncStateRepository extends JpaRepository<BiDeviceSyncState, UUID> {

  Optional<BiDeviceSyncState> findByUserIdAndDeviceId(UUID userId, String deviceId);

  /** {@code [templateVersion, devices]} for devices that synced since the given time. */
  @Query(
      """
      SELECT s.templateVersion, COUNT(s) FROM BiDeviceSyncState s
      WHERE s.lastSyncedAt >= :since
      GROUP BY s.templateVersion
      """)
  List<Object[]> countDevicesByVersionSince(@Param("since") OffsetDateTime since);
}
