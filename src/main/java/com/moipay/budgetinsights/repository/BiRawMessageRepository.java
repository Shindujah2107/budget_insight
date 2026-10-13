package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiRawMessage;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface BiRawMessageRepository extends JpaRepository<BiRawMessage, UUID> {

  /**
   * TTL purge. Retention here is short by design — see the entity notes.
   *
   * <p>The parameter is an {@code OffsetDateTime} because {@code ttlExpiresAt} is one. It was
   * declared as an {@code Instant} from R1 until BI-30, and Hibernate refuses the comparison
   * outright rather than coercing it. Nothing called this method in between, so the mismatch sat
   * in the repository looking correct: the first scheduled purge in production would have been
   * the first time anybody found out.
   */
  @Modifying
  @Transactional
  @Query("DELETE FROM BiRawMessage r WHERE r.ttlExpiresAt < :now")
  int deleteExpired(@Param("now") OffsetDateTime now);
}
