package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One due date of a customer's bill and how it was settled (V1.26.0). One row per bill and due
 * date; one payment settles at most one cycle. Personal: exported and erased with the rest of the
 * customer's data.
 */
@Entity
@Table(
    name = "bi_bill_cycle",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "ux_bi_bill_cycle_due",
          columnNames = {"user_bill_id", "due_on"}),
      @UniqueConstraint(
          name = "ux_bi_bill_cycle_txn",
          columnNames = {"transaction_id"})
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBillCycle {

  /** The customer said they paid it. */
  public static final String MARKED = "MARKED";
  /** The bills radar matched one of the customer's stored payments to it. */
  public static final String FOUND = "FOUND";
  /** The bill's own last payment already covered it when the cycle was first created. */
  public static final String RECORDED = "RECORDED";
  /** The customer said they did not pay it. */
  public static final String SKIPPED = "SKIPPED";

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "user_bill_id", nullable = false)
  private UUID userBillId;

  @Column(name = "due_on", nullable = false)
  private LocalDate dueOn;

  /** What the bill expected when this cycle began; the customer's own figure is never replaced. */
  @Column(name = "expected_amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal expectedAmount;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private BillCycleStatus status;

  @Column(name = "actual_amount", precision = 19, scale = 2)
  private BigDecimal actualAmount;

  @Column(name = "paid_on")
  private LocalDate paidOn;

  /** The payment that settled it (a bank SMS or a recorded payment), when one is known. */
  @Column(name = "transaction_id")
  private UUID transactionId;

  @Column(name = "settled_by", length = 16)
  private String settledBy;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
