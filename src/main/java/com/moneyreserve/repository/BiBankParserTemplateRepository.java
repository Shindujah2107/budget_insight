package com.moneyreserve.repository;

import com.moneyreserve.domain.BiBankParserTemplate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BiBankParserTemplateRepository extends JpaRepository<BiBankParserTemplate, UUID> {

  List<BiBankParserTemplate> findAllByIsActiveTrue();

  List<BiBankParserTemplate> findAllByBankCodeAndIsActiveTrue(String bankCode);

  /** Highest active template version, used as the reference-sync cache validator. */
  /**
   * The reference version handsets sync against: the latest admin release.
   *
   * <p>Before V1.5.0 this was the highest active {@code template_version}, which never moved when a
   * sender was added or a template retired. The release table is seeded at that same value, so the
   * number a device holds today stays valid until the first admin publish. Where no release row
   * exists (local H2 schemas built by Hibernate) the old definition still answers.
   */
  default int findCurrentTemplateVersion() {
    Integer release = findLatestReleaseVersion();
    return release != null ? release : findMaxActiveTemplateVersion();
  }

  @Query("SELECT MAX(r.version) FROM BiReferenceRelease r")
  Integer findLatestReleaseVersion();

  @Query(
      "SELECT COALESCE(MAX(t.templateVersion), 0) FROM BiBankParserTemplate t WHERE t.isActive = true")
  int findMaxActiveTemplateVersion();

  /** Highest version ever used for a bank and message type (active or retired); 0 if none. */
  @Query(
      "SELECT COALESCE(MAX(t.templateVersion), 0) FROM BiBankParserTemplate t "
          + "WHERE t.bankCode = :bankCode AND t.messageType = :messageType")
  int findMaxVersion(
      @Param("bankCode") String bankCode, @Param("messageType") String messageType);
}
