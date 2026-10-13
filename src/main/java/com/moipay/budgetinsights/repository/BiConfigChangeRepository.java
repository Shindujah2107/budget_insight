package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiConfigChange;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Pending admin changes (the shared changeset). */
public interface BiConfigChangeRepository extends JpaRepository<BiConfigChange, UUID> {

  List<BiConfigChange> findAllByOrderByUpdatedAtAscIdAsc();

  List<BiConfigChange> findAllByEntityType(ConfigEntityType entityType);

  Optional<BiConfigChange> findByEntityTypeAndTargetId(ConfigEntityType entityType, UUID targetId);
}
