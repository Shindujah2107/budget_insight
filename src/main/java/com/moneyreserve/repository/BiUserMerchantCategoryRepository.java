package com.moneyreserve.repository;

import com.moneyreserve.domain.BiUserMerchantCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiUserMerchantCategoryRepository
    extends JpaRepository<BiUserMerchantCategory, UUID> {

  Optional<BiUserMerchantCategory> findByUserIdAndMerchantKey(UUID userId, String merchantKey);

  List<BiUserMerchantCategory> findAllByUserIdOrderByMerchantKeyAsc(UUID userId);
}
