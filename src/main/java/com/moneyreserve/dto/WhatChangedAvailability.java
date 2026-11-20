package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Whether "What changed?" can be shown to this customer (V1.28.0): the global switch and their
 * Premium entitlement, decided on the server. {@code available} only when both hold.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class WhatChangedAvailability {
  /** The feature's stable key: {@code insights.what-changed}. */
  private String featureKey;
  /** The global switch (Admin › Spending › Budget policy). */
  private boolean enabled;
  /** This customer is entitled to Premium today. */
  private boolean entitled;
  private boolean available;
  /** Why not: FEATURE_DISABLED (checked first) or PREMIUM_REQUIRED; null when available. */
  private String reason;
}
