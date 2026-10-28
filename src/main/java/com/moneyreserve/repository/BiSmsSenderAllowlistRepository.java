package com.moneyreserve.repository;

import com.moneyreserve.domain.BiSmsSenderAllowlist;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiSmsSenderAllowlistRepository extends JpaRepository<BiSmsSenderAllowlist, UUID> {

  List<BiSmsSenderAllowlist> findAllByIsActiveTrue();

  boolean existsBySenderIdAndIsActiveTrue(String senderId);
}
