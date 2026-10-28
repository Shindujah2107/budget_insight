package com.moneyreserve.repository;

import com.moneyreserve.domain.BiBank;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiBankRepository extends JpaRepository<BiBank, String> {

  List<BiBank> findAllByIsActiveTrue();

  Optional<BiBank> findFirstByBankNameIgnoreCase(String bankName);

  List<BiBank> findAllByIsPendingTrue();
}
