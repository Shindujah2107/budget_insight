package com.moneyreserve.repository;

import com.moneyreserve.domain.BiAppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BiAppUserRepository extends JpaRepository<BiAppUser, UUID> {

  Optional<BiAppUser> findByPhone(String phone);
}
