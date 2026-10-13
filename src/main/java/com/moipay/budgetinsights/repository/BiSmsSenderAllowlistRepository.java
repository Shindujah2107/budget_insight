package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiSmsSenderAllowlist;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiSmsSenderAllowlistRepository extends JpaRepository<BiSmsSenderAllowlist, UUID> {

  List<BiSmsSenderAllowlist> findAllByIsActiveTrue();

  boolean existsBySenderIdAndIsActiveTrue(String senderId);
}
