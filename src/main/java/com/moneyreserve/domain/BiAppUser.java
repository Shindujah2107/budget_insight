package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer of the standalone MoneyReserve app, identified by a verified mobile number.
 *
 * <p>The {@code id} is the user id every Insights table keys on: transactions, budgets and consent
 * all belong to it.
 *
 * <p>A row exists from the moment a code is requested on the register screen; {@code verifiedAt}
 * is set when the code is entered. An unverified row holds no data and is not an account: it can
 * be re-registered (its name and email are replaced) and cannot log in.
 */
@Entity
@Table(
    name = "bi_app_user",
    uniqueConstraints = @UniqueConstraint(name = "uq_bi_app_user_phone", columnNames = "phone"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAppUser {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;


  /** E.164, e.g. {@code +94771234521}. */
  @Column(name = "phone", nullable = false, length = 16)
  private String phone;

  @Column(name = "full_name", nullable = false, length = 120)
  private String fullName;

  @Column(name = "email", length = 254)
  private String email;

  /** CUSTOMER unless the phone is listed as a staff number in configuration. */
  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 16)
  @Builder.Default
  private AppRole role = AppRole.CUSTOMER;

  @Column(name = "verified_at")
  private OffsetDateTime verifiedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (this.id == null) {
      this.id = UUID.randomUUID();
    }
    if (this.role == null) {
      this.role = AppRole.CUSTOMER;
    }
    this.createdAt = OffsetDateTime.now();
    this.updatedAt = this.createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = OffsetDateTime.now();
  }

  public boolean isVerified() {
    return verifiedAt != null;
  }
}
