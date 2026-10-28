package com.moneyreserve.service.impl;

import com.moneyreserve.config.Caller;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBank;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiDeviceSyncState;
import com.moneyreserve.domain.BiSmsSenderAllowlist;
import com.moneyreserve.dto.ParserTemplateDto;
import com.moneyreserve.dto.ReferenceSyncResponse;
import com.moneyreserve.dto.SenderAllowlistDto;
import com.moneyreserve.dto.SmsVocabularyDto;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiBankRepository;
import com.moneyreserve.repository.BiDeviceSyncStateRepository;
import com.moneyreserve.repository.BiSmsSenderAllowlistRepository;
import com.moneyreserve.service.ConsentService;
import com.moneyreserve.service.ReferenceSyncService;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the device its reading rules (BI-13).
 *
 * <p>Behind the consent gate, like ingest itself. Reference data, not customer data: every device
 * that passes that gate gets the same allowlist and the same
 * templates. The only per-customer thing here is {@code bi_device_sync_state}, which records that
 * this handset has now been told, so support can tell a device that never synced from one that
 * synced and then ignored what it was given.
 *
 * <p>It is served rather than shipped in the app for one reason worth stating plainly: when a bank
 * changes the wording of its alerts, every customer on that bank silently stops having their spend
 * captured. Fixing that with a row update takes an afternoon. Fixing it with an app release takes
 * two store review queues, during which the screen quietly under-reports.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferenceSyncServiceImpl implements ReferenceSyncService {

  private final ConsentService consent;
  private final BiSmsSenderAllowlistRepository senders;
  private final BiBankRepository banks;
  private final BiBankParserTemplateRepository templates;
  private final BiDeviceSyncStateRepository syncStates;
  private final InsightsProperties properties;
  private final SmsVocabulary vocabulary;

  /**
   * The current reference set.
   *
   * <p><b>The payload is served whether or not the device is up to date.</b> A conditional response
   * would save a few kilobytes and cost the one guarantee that matters: that a device which calls
   * this has the rules afterwards. A handset that lost its local store but kept its version number
   * is exactly the case a 304-style answer would strand.
   */
  @Override
  @Transactional
  public ReferenceSyncResponse sync(
      Caller caller, String deviceId, Integer templateVersion, Integer vocabularyVersion) {

    // Handing a device the rules for reading bank messages is not itself reading one, but it has
    // no purpose except to enable it. A customer who declined, or who has withdrawn, should not
    // find their handset still being told how to parse -- and the controller's contract says this
    // route is behind the same gate as ingest, so something has to actually hold it.
    consent.requireActiveConsent(caller.userId());

    int serverVersion = templates.findCurrentTemplateVersion();
    Map<String, String> bankNames =
        banks.findAll().stream().collect(Collectors.toMap(BiBank::getBankCode, BiBank::getBankName));
    List<SenderAllowlistDto> allowlist =
        senders.findAllByIsActiveTrue().stream()
            .sorted(
                Comparator.comparing(BiSmsSenderAllowlist::getBankCode)
                    .thenComparing(BiSmsSenderAllowlist::getSenderId))
            .map(
                sender ->
                    SenderAllowlistDto.builder()
                        .bankCode(sender.getBankCode())
                        .bankName(bankNames.get(sender.getBankCode()))
                        .senderId(sender.getSenderId())
                        .countryCode(sender.getCountryCode())
                        .build())
            .toList();

    List<ParserTemplateDto> parsers =
        templates.findAllByIsActiveTrue().stream()
            .sorted(
                Comparator.comparing(BiBankParserTemplate::getBankCode)
                    .thenComparing(BiBankParserTemplate::getMessageType)
                    .thenComparing(BiBankParserTemplate::getTemplateVersion))
            .map(
                template ->
                    ParserTemplateDto.builder()
                        .id(template.getId())
                        .bankCode(template.getBankCode())
                        .messageType(template.getMessageType())
                        .pattern(template.getPattern())
                        .fieldMap(template.getFieldMap())
                        .templateVersion(template.getTemplateVersion())
                        .build())
            .toList();

    OffsetDateTime previous = recordSync(caller.userId(), deviceId, serverVersion);

    if (allowlist.isEmpty() || parsers.isEmpty()) {
      // Expected until dependency D-1 lands. Logged at ERROR rather than INFO because a device that
      // syncs and receives nothing will read no messages at all, and "capture silently does
      // nothing" is a state somebody should be able to find in a log without being told to look.
      log.error(
          "Insights reference sync served an incomplete set: senders={} templates={}."
              + " The allowlist and parser templates are unseeded pending the labelled sample set.",
          allowlist.size(),
          parsers.size());
    }

    log.info(
        "Insights reference sync userId={} device={} deviceVersion={} serverVersion={} senders={} templates={} senderIds={}",
        caller.userId(),
        fingerprint(deviceId),
        templateVersion,
        serverVersion,
        allowlist.size(),
        parsers.size(),
        allowlist.stream().map(s -> s.getBankCode() + ":" + s.getSenderId()).toList());

    return ReferenceSyncResponse.builder()
        .vocabulary(vocabularyFor(vocabularyVersion))
        .templateVersion(serverVersion)
        .upToDate(templateVersion != null && templateVersion == serverVersion)
        .senders(allowlist)
        .templates(parsers)
        .previousSyncedAt(previous)
        .syncedAt(OffsetDateTime.now())
        .build();
  }

  /**
   * The SMS format-discovery vocabulary, only for a phone that asked for it by sending its version
   * and is behind. Everything else — older apps, Phase 2 off, phone up to date — gets no field at
   * all, so this route's response is unchanged for every client that predates the feature.
   */
  private SmsVocabularyDto vocabularyFor(Integer phoneVersion) {
    if (phoneVersion == null
        || properties == null
        || vocabulary == null
        || !properties.getFormatDiscovery().isEnabled()
        || !properties.getFormatDiscovery().isSamplesEnabled()) {
      return null;
    }
    SmsVocabularyDto current = vocabulary.forDevice();
    return current.getVersion() > phoneVersion ? current : null;
  }

  /**
   * Marks this device as having been served, and returns when it last was.
   *
   * <p>A device that sends no identifier is served but not recorded — the reference set is not
   * secret and refusing it would break a client for no benefit, but there is nothing to write a row
   * against.
   */
  private OffsetDateTime recordSync(UUID userId, String deviceId, int serverVersion) {
    if (deviceId == null || deviceId.isBlank()) {
      log.info("Insights reference sync without a device id userId={}: served, not recorded", userId);
      return null;
    }
    BiDeviceSyncState state =
        syncStates
            .findByUserIdAndDeviceId(userId, deviceId)
            .orElseGet(
                () ->
                    BiDeviceSyncState.builder()
                        .userId(userId)
                        .deviceId(deviceId)
                        .lastIngestSeq(0L)
                        .templateVersion(0)
                        .build());
    OffsetDateTime previous = state.getLastSyncedAt();
    state.setTemplateVersion(serverVersion);
    state.setLastSyncedAt(OffsetDateTime.now());
    syncStates.save(state);
    return previous;
  }

  private String fingerprint(String deviceId) {
    if (deviceId == null || deviceId.isBlank()) {
      return "-";
    }
    String trimmed = deviceId.trim();
    return trimmed.length() <= 6 ? trimmed : trimmed.substring(0, 6) + "…";
  }
}
