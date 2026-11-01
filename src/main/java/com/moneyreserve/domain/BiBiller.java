package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * A known company that sends bills (CEB, Dialog, Netflix, an insurer…), managed in the Admin
 * Dashboard and published like categories. Payments whose merchant name matches its terms are one
 * bill even before they look regular, and show under its name, icon and colour. Shared reference
 * data: holds nothing about any customer.
 */
@Entity
@Table(name = "bi_biller")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiBiller {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "code", nullable = false, length = 32, unique = true)
  private String code;

  @Column(name = "name_en", nullable = false, length = 64)
  private String nameEn;

  @Column(name = "name_si", length = 64)
  private String nameSi;

  @Column(name = "name_ta", length = 64)
  private String nameTa;

  @Enumerated(EnumType.STRING)
  @Column(name = "bill_type", nullable = false, length = 16)
  private BillType billType;

  /** Name terms, as merchant rules write them ({@code DIALOG|DIALOG AXIATA|-REFUND}). */
  @Column(name = "match_terms", nullable = false, length = 1000)
  private String matchTerms;

  @Column(name = "icon_key", nullable = false, length = 48)
  private String iconKey;

  @Column(name = "colour_hex", length = 7)
  private String colourHex;

  /** ±% one payment may differ from the usual; empty means the bill type's default. */
  @Column(name = "amount_tolerance_pct")
  private Short amountTolerancePct;

  @Column(name = "sort_order", nullable = false)
  private Short sortOrder;

  @Column(name = "is_active", nullable = false)
  private Boolean isActive;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;

  public int tolerancePct() {
    return amountTolerancePct != null ? amountTolerancePct : billType.defaultTolerancePct();
  }
}
