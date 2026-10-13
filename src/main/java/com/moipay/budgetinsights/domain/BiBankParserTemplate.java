package com.moipay.budgetinsights.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-bank message template used to extract transaction fields on the device.
 *
 * <p>Templates are served to the device through the reference sync endpoint, so adding support for
 * a new message format is a data change rather than an app release. Where no template matches, the
 * on-device fallback produces the same field set and the message is flagged so a template can be
 * added.
 */
@Entity
@Table(
    name = "bi_bank_parser_template",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_tpl_bank_type_version",
          columnNames = {"bank_code", "message_type", "template_version"})
    },
    indexes = {
      @Index(name = "ix_bi_tpl_active", columnList = "is_active, bank_code, message_type")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBankParserTemplate {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "bank_code", nullable = false, length = 16)
  private String bankCode;

  /** Expenditure, credit card, debit card, online payment, biller payment, and so on. */
  @Column(name = "message_type", nullable = false, length = 32)
  private String messageType;

  @Column(name = "pattern", nullable = false, columnDefinition = "text")
  private String pattern;

  /** Maps capture groups to transaction fields. */
  // Required, not decoration: without it Hibernate binds via setString and PostgreSQL
  // rejects the insert with 42804 (column is jsonb, expression is character varying).
  // Matches DserEventLog.payload, BillPaymentLog and AccountClosureRequest.
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "field_map", nullable = false, columnDefinition = "jsonb")
  private String fieldMap;

  @Column(name = "template_version", nullable = false)
  private Integer templateVersion;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /**
   * The admin template wizard's definition: the bank's fixed wording and the field slots between
   * it. Never sample SMS text, and never sent to handsets ({@code ParserTemplateDto} does not carry
   * it). NULL for seeded templates and for templates saved in Advanced mode.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "builder_spec", columnDefinition = "jsonb")
  private String builderSpec;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.isActive == null) {
      this.isActive = Boolean.TRUE;
    }
    if (this.templateVersion == null) {
      this.templateVersion = 1;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }
}
