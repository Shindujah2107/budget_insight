package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One customer's recurring bill: found in their own payments or added by hand. The payee key is
 * the biller code ({@code B:CEB}) or the merchant as {@code MerchantRuleMatcher.normalise} reads it
 * ({@code M:KOTTU CORNER}); a manual bill has its own ({@code X:<id>}). Personal: exported and
 * erased with the rest of the customer's data.
 */
@Entity
@Table(
    name = "bi_user_bill",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_user_bill_payee",
          columnNames = {"user_id", "payee_key"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiUserBill {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "payee_key", nullable = false, length = 160)
  private String payeeKey;

  @Column(name = "biller_id")
  private UUID billerId;

  /** The name detection found (biller or merchant); shown unless the customer renamed it. */
  @Column(name = "detected_name", nullable = false, length = 128)
  private String detectedName;

  @Column(name = "custom_name", length = 64)
  private String customName;

  /** The category the customer chose for this bill (V1.24.0); its payments are filed there. */
  @Column(name = "category_id")
  private UUID categoryId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private BillStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "cadence", nullable = false, length = 16)
  private BillCadence cadence;

  /** The usual amount: the median of the last three payments, or the customer's own figure. */
  @Column(name = "expected_amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal expectedAmount;

  @Column(name = "last_amount", precision = 19, scale = 2)
  private BigDecimal lastAmount;

  @Column(name = "last_paid_on")
  private LocalDate lastPaidOn;

  @Column(name = "next_due_on", nullable = false)
  private LocalDate nextDueOn;

  /** Day of the month it falls on (31 = month-end), for monthly, quarterly and yearly bills. */
  @Column(name = "anchor_day")
  private Short anchorDay;

  @Column(name = "remind_days_before", nullable = false)
  private Short remindDaysBefore;

  @Column(name = "payment_count", nullable = false)
  private Short paymentCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public String displayName() {
    return customName != null && !customName.isBlank() ? customName : detectedName;
  }
}
