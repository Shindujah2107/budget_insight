package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiBank;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiBankRepository extends JpaRepository<BiBank, String> {

  List<BiBank> findAllByIsActiveTrue();

  Optional<BiBank> findFirstByBankNameIgnoreCase(String bankName);

  List<BiBank> findAllByIsPendingTrue();
}
