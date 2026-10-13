package com.moipay.budgetinsights.repository;

import com.moipay.budgetinsights.domain.BiSmsFormatCandidate;
import com.moipay.budgetinsights.domain.FormatCandidateStatus;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiSmsFormatCandidateRepository extends JpaRepository<BiSmsFormatCandidate, UUID> {

  Optional<BiSmsFormatCandidate> findBySenderIdAndFormatHash(String senderId, String formatHash);

  /**
   * What an admin may see. The floor is applied here, in the query, so no code path can list a
   * format fewer than {@code minUsers} people sent.
   *
   * <p>Two queries rather than one with {@code (:sender IS NULL OR …)}: PostgreSQL cannot type a
   * bare parameter inside {@code IS NULL} (see {@code BiTransactionRepository}).
   */
  @Query(
      "SELECT c FROM BiSmsFormatCandidate c WHERE c.status = :status AND c.distinctUsers >= :minUsers "
          + "ORDER BY c.distinctUsers DESC, c.lastSeenAt DESC, c.id")
  Page<BiSmsFormatCandidate> findVisible(
      @Param("status") FormatCandidateStatus status,
      @Param("minUsers") int minUsers,
      Pageable page);

  @Query(
      "SELECT c FROM BiSmsFormatCandidate c WHERE c.status = :status AND c.distinctUsers >= :minUsers "
          + "AND c.senderId = :sender ORDER BY c.distinctUsers DESC, c.lastSeenAt DESC, c.id")
  Page<BiSmsFormatCandidate> findVisibleBySender(
      @Param("status") FormatCandidateStatus status,
      @Param("minUsers") int minUsers,
      @Param("sender") String sender,
      Pageable page);

  List<BiSmsFormatCandidate> findByStatus(FormatCandidateStatus status);

  /** Candidates with no live sample left that were last seen before {@code cutoff}. */
  @Query(
      "SELECT c.id FROM BiSmsFormatCandidate c WHERE c.lastSeenAt < :cutoff AND NOT EXISTS "
          + "(SELECT 1 FROM BiSmsFormatSample s WHERE s.candidateId = c.id)")
  List<UUID> findAbandoned(@Param("cutoff") OffsetDateTime cutoff);

  /**
   * Sets distinct_users from the live samples. A bulk statement, not an entity save, so it takes
   * effect at once: erasure clears the persistence context afterwards, which would silently drop
   * an unflushed change to a managed entity.
   */
  @Modifying
  @Query(
      "UPDATE BiSmsFormatCandidate c SET c.distinctUsers = "
          + "(SELECT COUNT(s) FROM BiSmsFormatSample s WHERE s.candidateId = c.id) WHERE c.id IN :ids")
  int recount(@Param("ids") Collection<UUID> ids);

  /** Adds accepted samples in place, so concurrent uploads never lose each other's counts. */
  @Modifying
  @Query("UPDATE BiSmsFormatCandidate c SET c.sampleCount = c.sampleCount + :n WHERE c.id = :id")
  int addSamples(@Param("id") UUID id, @Param("n") int n);

  /** The same, for every format: the daily pass corrects any drift. */
  @Modifying
  @Query(
      "UPDATE BiSmsFormatCandidate c SET c.distinctUsers = "
          + "(SELECT COUNT(s) FROM BiSmsFormatSample s WHERE s.candidateId = c.id)")
  int recountAll();

  @Modifying
  @Query("DELETE FROM BiSmsFormatCandidate c WHERE c.id IN :ids")
  int deleteByIds(@Param("ids") List<UUID> ids);
}
