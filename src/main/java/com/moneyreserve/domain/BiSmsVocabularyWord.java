package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import lombok.*;

/**
 * One word a handset may name in an SMS format skeleton, by its id.
 *
 * <p><b>Ids are permanent.</b> Phones cache the vocabulary and send ids, so an id that changed its
 * word would silently change what every cached phone means. A word is retired by switching
 * {@code isActive} off and setting {@code retiredInVersion} to a new version — which is what makes
 * phones fetch the list again — never renumbered and never reused.
 *
 * <p>The id is assigned, not generated: the seed and every later addition choose it explicitly.
 */
@Entity
@Table(
    name = "bi_sms_vocabulary",
    uniqueConstraints = {@UniqueConstraint(name = "ux_bi_sms_vocabulary_word", columnNames = "word")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiSmsVocabularyWord {

  @Id
  @Column(name = "id", nullable = false)
  private Integer id;

  /** Upper-case letters only; the database refuses anything else. */
  @Column(name = "word", nullable = false, length = 24)
  private String word;

  /** FUNCTION, BANKING, BANK or TEMPLATE: where the word came from. */
  @Column(name = "source", nullable = false, length = 12)
  private String source;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "added_in_version", nullable = false)
  private Integer addedInVersion;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  /** Set when the word is retired; that version is newer, so phones fetch the list again. */
  @Column(name = "retired_in_version")
  private Integer retiredInVersion;

  /** The vocabulary version this row last changed in. */
  public int changedInVersion() {
    return retiredInVersion == null ? addedInVersion : Math.max(addedInVersion, retiredInVersion);
  }

  @PrePersist
  protected void onCreate() {
    if (this.createdAt == null) {
      this.createdAt = OffsetDateTime.now();
    }
    if (this.isActive == null) {
      this.isActive = Boolean.TRUE;
    }
  }
}
