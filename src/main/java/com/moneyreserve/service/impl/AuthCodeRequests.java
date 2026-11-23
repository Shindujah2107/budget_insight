package com.moneyreserve.service.impl;

import com.moneyreserve.config.AuthProperties;
import com.moneyreserve.domain.BiAuthCodeRequest;
import com.moneyreserve.repository.BiAuthCodeRequestRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The record behind the sign-in code limits (V1.32.0). A request is written in its own transaction
 * before the SMS is sent, so a send that fails (and rolls the code back) still counts: retrying
 * through a Text.lk outage cannot get round the limits. Phone numbers and network addresses are
 * stored only as hashes salted with the sign-in signing key.
 */
@Component
@RequiredArgsConstructor
public class AuthCodeRequests {

  /** How long a request row is kept: the longest window counted is one day. */
  static final Duration KEEP = Duration.ofDays(2);

  private final BiAuthCodeRequestRepository requests;
  private final AuthProperties auth;

  public String phoneKey(String phone) {
    return hash("phone", phone);
  }

  /** Null for an unknown address (nothing is counted per address then). */
  public String ipKey(String clientIp) {
    return clientIp == null || clientIp.isBlank() ? null : hash("ip", clientIp.trim());
  }

  @Transactional(readOnly = true)
  public long forPhoneSince(String phoneKey, OffsetDateTime since) {
    return requests.countByPhoneHashAndCreatedAtAfter(phoneKey, since);
  }

  @Transactional(readOnly = true)
  public long forIpSince(String ipKey, OffsetDateTime since) {
    return ipKey == null ? 0 : requests.countByIpHashAndCreatedAtAfter(ipKey, since);
  }

  @Transactional(readOnly = true)
  public long allSince(OffsetDateTime since) {
    return requests.countByCreatedAtAfter(since);
  }

  /** Records one request, committed at once whatever happens to the sign-in transaction after. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String phoneKey, String ipKey, OffsetDateTime now) {
    requests.deleteOlderThan(now.minus(KEEP));
    requests.save(
        BiAuthCodeRequest.builder().id(UUID.randomUUID()).phoneHash(phoneKey).ipHash(ipKey).createdAt(now).build());
  }

  private String hash(String kind, String value) {
    return JwtTokenService.sha256(kind + "|" + auth.getJwtSecret() + "|" + value);
  }
}
