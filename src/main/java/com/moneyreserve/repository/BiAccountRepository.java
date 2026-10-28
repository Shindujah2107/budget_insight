package com.moneyreserve.repository;

import com.moneyreserve.domain.InstrumentType;
import com.moneyreserve.domain.BiAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiAccountRepository extends JpaRepository<BiAccount, UUID> {

  List<BiAccount> findAllByUserIdAndDeletedAtIsNullAndIsActiveTrue(UUID userId);

  /** Resolution key during ingest — an account is whatever tail a bank message carried. */
  Optional<BiAccount> findByUserIdAndBankCodeAndInstrumentTypeAndMaskedTailAndDeletedAtIsNull(
      UUID userId, String bankCode, InstrumentType instrumentType, String maskedTail);

  /** Always resolve by id together with the user, never by id alone. */
  Optional<BiAccount> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

  /** Customer accounts at a bank (any state): customer data that keeps a bank from being deleted. */
  long countByBankCode(String bankCode);
}
