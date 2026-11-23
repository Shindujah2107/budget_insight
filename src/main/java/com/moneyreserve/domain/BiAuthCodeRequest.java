package com.moneyreserve.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.*;

/**
 * One "send me a sign-in code" request (V1.32.0), counted by the request limits whether or not the
 * SMS went out. Salted hashes only: no phone number, no network address. Kept two days.
 */
@Entity
@Table(name = "bi_auth_code_request")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BiAuthCodeRequest {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "phone_hash", nullable = false, length = 64)
  private String phoneHash;

  @Column(name = "ip_hash", length = 64)
  private String ipHash;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;
}
