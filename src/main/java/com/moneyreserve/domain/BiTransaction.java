package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.UUID;
import lombok.*;

/**
 * The spending ledger behind every figure on the Insights screen.
 *
 * <p>Nothing on the screen is stored as a displayed value. Utilisation, the weekly series, category
 * shares and the period total are all derived from these rows at read time, which is why the
 * aggregate cache is always safe to throw away and rebuild.
 *
 * <p>Two fields carry more weight than their size suggests:
 *
 * <ul>
 *   <li>{@link #idempotencyKey} is computed on the device and is what makes repeated ingest of the
 *       same bank message harmless. It is unique per user, not globally.
 *   <li>{@link #isUserCategorised} is a one-way latch. Once the customer has corrected a category,
 *       the categorisation engine must never overwrite it — not on re-processing, and not when the
 *       merchant map improves. {@link #autoCategoryId} keeps what the engine thought, so the
 *       mapping can still be measured against what the customer actually chose.
 * </ul>
 */
@Entity
@Table(
    name = "bi_transaction",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_txn_user_idem",
          columnNames = {"user_id", "idempotency_key"})
    },
    indexes = {
      @Index(name = "ix_bi_txn_user_date", columnList = "user_id, txn_date"),
      @Index(name = "ix_bi_txn_user_cat_date", columnList = "user_id, category_id, txn_date"),
      @Index(name = "ix_bi_txn_user_acct_date", columnList = "user_id, account_id, txn_date")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiTransaction {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;


  /** Null for manually added cash spend, which belongs to no instrument. */
  @Column(name = "account_id")
  private UUID accountId;

  /** Always positive. The sign shown to the customer comes from {@link #direction}. */
  @Column(name = "amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "direction", nullable = false, length = 8)
  private TxnDirection direction;

  @Column(name = "merchant", length = 128)
  private String merchant;

  /** Optional customer note, manual entries only. */
  @Column(name = "description", length = 256)
  private String description;

  /** Local date used for all period bucketing. */
  @Column(name = "txn_date", nullable = false)
  private LocalDate txnDate;

  /** Orders transactions within a day. */
  @Column(name = "txn_timestamp")
  private OffsetDateTime txnTimestamp;

  @Column(name = "category_id", nullable = false)
  private UUID categoryId;

  /** What the engine assigned. Retained after a customer override for measurement. */
  @Column(name = "auto_category_id")
  private UUID autoCategoryId;

  @Column(name = "is_user_categorised", nullable = false)
  private Boolean isUserCategorised;

  /** Why the row has its category (V1.17.0). See {@link CategorySource}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "category_source", length = 16)
  private CategorySource categorySource;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, length = 16)
  private TxnSource source;

  /** Null where the on-device fallback produced the fields rather than a template. */
  @Column(name = "parser_template_id")
  private UUID parserTemplateId;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  /** {@code CASH_WALLET} when paid from (or into) the cash wallet (V1.21.0); null otherwise. */
  @Column(name = "payment_source", length = 16)
  private String paymentSource;

  /**
   * A confirmed movement of the customer's own money (V1.21.0): {@code BANK_TO_CASH},
   * {@code CASH_TO_BANK}, {@code OWN_ACCOUNT} or {@code BALANCE_CORRECTION}. Such a row is neither
   * spending nor income. Null for everything else.
   */
  @Column(name = "movement", length = 24)
  private String movement;

  /**
   * Where a payment the customer recorded came from (V1.23.0): {@code ATTEMPT} when they confirmed
   * that a payment their bank announced as attempted went through, {@code BILL} when they marked a
   * bill paid. Null for everything else. Such a row waits for the bank's own SMS, which is then
   * linked to it instead of being counted again.
   */
  @Column(name = "origin", length = 16)
  private String origin;

  /**
   * The bank (V1.23.0): for a recorded payment, the bank that announced the attempt, when known;
   * for a bank SMS, the bank it came from. Used to pair the two.
   */
  @Column(name = "origin_bank_code", length = 16)
  private String originBankCode;

  /** The bill this payment paid (V1.23.0). */
  @Column(name = "user_bill_id")
  private UUID userBillId;

  /**
   * The idempotency key of the bank SMS or confirmed payment this row was matched with
   * (V1.23.0): set on both sides, so one SMS pairs with one recorded payment and a resent SMS
   * stays a duplicate.
   */
  @Column(name = "linked_key", length = 128)
  private String linkedKey;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  /** Soft delete, so the audit trail of a removed transaction survives. */
  @Column(name = "deleted_at")
  private OffsetDateTime deletedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    OffsetDateTime now = OffsetDateTime.now();
    this.createdAt = now;
    this.updatedAt = now;
    if (this.currency == null) {
      this.currency = "LKR";
    }
    if (this.isUserCategorised == null) {
      this.isUserCategorised = Boolean.FALSE;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  /** True when this row counts towards spend figures. */
  public boolean countsAsSpend() {
    return !isDeleted() && TxnDirection.DEBIT.equals(direction);
  }
}
