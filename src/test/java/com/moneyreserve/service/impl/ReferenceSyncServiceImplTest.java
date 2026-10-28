package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moneyreserve.config.Caller;
import com.moneyreserve.domain.BiBankParserTemplate;
import com.moneyreserve.domain.BiDeviceSyncState;
import com.moneyreserve.domain.BiSmsSenderAllowlist;
import com.moneyreserve.dto.ReferenceSyncResponse;
import com.moneyreserve.repository.BiBankParserTemplateRepository;
import com.moneyreserve.repository.BiDeviceSyncStateRepository;
import com.moneyreserve.repository.BiSmsSenderAllowlistRepository;
import com.moneyreserve.service.ConsentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reference sync (BI-13).
 *
 * <p>The behaviour worth protecting here is the one that looks like an inefficiency: the payload is
 * served even to a device that says it is already up to date. A handset that lost its local store
 * but kept its version number is exactly the case a conditional answer would strand, and it would
 * strand it silently — the device would report itself healthy and capture nothing.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceSyncServiceImplTest {

  @Mock private ConsentService consent;
  @Mock private BiSmsSenderAllowlistRepository senders;
  @Mock private BiBankParserTemplateRepository templates;
  @Mock private BiDeviceSyncStateRepository syncStates;

  @InjectMocks private ReferenceSyncServiceImpl service;

  private static final UUID USER = UUID.randomUUID();
  private static final Caller CALLER = new Caller(USER);

  @Test
  @DisplayName("Serves the allowlist and the templates, sorted so two devices see one order")
  void servesTheReferenceSet() {
    when(templates.findCurrentTemplateVersion()).thenReturn(7);
    when(senders.findAllByIsActiveTrue())
        .thenReturn(List.of(sender("SAMP", "SampathBank"), sender("COMB", "ComBank")));
    when(templates.findAllByIsActiveTrue())
        .thenReturn(List.of(template("SAMP", "CARD_PURCHASE"), template("COMB", "CARD_PURCHASE")));
    stubDeviceState(null);

    ReferenceSyncResponse response = service.sync(CALLER, "device-1", 5);

    assertEquals(7, response.getTemplateVersion());
    assertFalse(response.isUpToDate());
    assertEquals(2, response.getSenders().size());
    assertEquals(2, response.getTemplates().size());
    // Sorted by bank code, so a diff between two handsets is a real difference.
    assertEquals("COMB", response.getSenders().get(0).getBankCode());
    assertEquals("COMB", response.getTemplates().get(0).getBankCode());
    assertNotNull(response.getSyncedAt());
  }

  @Test
  @DisplayName("An up-to-date device is told so, and still gets the payload")
  void upToDateDeviceStillReceivesTheSet() {
    when(templates.findCurrentTemplateVersion()).thenReturn(7);
    when(senders.findAllByIsActiveTrue()).thenReturn(List.of(sender("COMB", "ComBank")));
    when(templates.findAllByIsActiveTrue())
        .thenReturn(List.of(template("COMB", "CARD_PURCHASE")));
    stubDeviceState(null);

    ReferenceSyncResponse response = service.sync(CALLER, "device-1", 7);

    assertTrue(response.isUpToDate());
    // The point of the test: up to date is not the same as nothing to send.
    assertEquals(1, response.getSenders().size());
    assertEquals(1, response.getTemplates().size());
  }

  @Test
  @DisplayName("Empty lists are the correct answer while the sample set has not arrived")
  void unseededReferenceDataIsNotAnError() {
    when(templates.findCurrentTemplateVersion()).thenReturn(0);
    when(senders.findAllByIsActiveTrue()).thenReturn(List.of());
    when(templates.findAllByIsActiveTrue()).thenReturn(List.of());
    stubDeviceState(null);

    ReferenceSyncResponse response = service.sync(CALLER, "device-1", null);

    // Seeding invented sender tags and guessed patterns would produce a parser whose accuracy
    // nobody could evidence, on the one path where accuracy is a compliance matter (D-1).
    assertEquals(0, response.getTemplateVersion());
    assertFalse(response.isUpToDate());
    assertTrue(response.getSenders().isEmpty());
    assertTrue(response.getTemplates().isEmpty());
  }

  @Test
  @DisplayName("A device with no stated version is never reported as up to date")
  void nullVersionIsNotUpToDate() {
    when(templates.findCurrentTemplateVersion()).thenReturn(3);
    when(senders.findAllByIsActiveTrue()).thenReturn(List.of());
    when(templates.findAllByIsActiveTrue()).thenReturn(List.of());
    stubDeviceState(null);

    assertFalse(service.sync(CALLER, "device-1", null).isUpToDate());
  }

  @Test
  @DisplayName("The device's position is advanced, and its previous sync reported back")
  void recordsTheDevicePosition() {
    OffsetDateTime before = OffsetDateTime.now().minusDays(2);
    when(templates.findCurrentTemplateVersion()).thenReturn(9);
    when(senders.findAllByIsActiveTrue()).thenReturn(List.of());
    when(templates.findAllByIsActiveTrue()).thenReturn(List.of());
    stubDeviceState(before);

    ReferenceSyncResponse response = service.sync(CALLER, "device-1", 1);

    assertEquals(before, response.getPreviousSyncedAt());
    ArgumentCaptor<BiDeviceSyncState> captor = ArgumentCaptor.forClass(BiDeviceSyncState.class);
    verify(syncStates).save(captor.capture());
    // Support's question is "did this handset ever get the rules", and this is the row that
    // answers it.
    assertEquals(9, captor.getValue().getTemplateVersion());
    assertNotNull(captor.getValue().getLastSyncedAt());
  }

  @Test
  @DisplayName("A caller with no device identifier is served but not recorded")
  void anonymousDeviceIsServedWithoutARow() {
    when(templates.findCurrentTemplateVersion()).thenReturn(2);
    when(senders.findAllByIsActiveTrue()).thenReturn(List.of());
    when(templates.findAllByIsActiveTrue()).thenReturn(List.of());

    ReferenceSyncResponse response = service.sync(CALLER, null, 2);

    // The reference set is not secret; refusing it would break a client for no benefit. There is
    // simply nothing to write a row against.
    assertTrue(response.isUpToDate());
    assertNull(response.getPreviousSyncedAt());
    verify(syncStates, never()).save(any());
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  private void stubDeviceState(OffsetDateTime lastSyncedAt) {
    when(syncStates.findByUserIdAndDeviceId(eq(USER), anyString()))
        .thenReturn(
            Optional.of(
                BiDeviceSyncState.builder()
                    .id(UUID.randomUUID())
                    .userId(USER)
                    .deviceId("device-1")
                    .lastIngestSeq(0L)
                    .templateVersion(0)
                    .lastSyncedAt(lastSyncedAt)
                    .build()));
    when(syncStates.save(any(BiDeviceSyncState.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("A customer who has withdrawn consent is not handed the rules for reading messages")
  void consentGatesTheReferenceSet() {
    org.mockito.Mockito.doThrow(
            new com.moneyreserve.exception.BudgetInsightsException(
                com.moneyreserve.exception.ErrorCode.INSIGHTS_CONSENT_REQUIRED))
        .when(consent)
        .requireActiveConsent(USER);

    org.junit.jupiter.api.Assertions.assertThrows(
        com.moneyreserve.exception.BudgetInsightsException.class,
        () -> service.sync(CALLER, "device-1", 1));

    // Nothing read, nothing recorded. The route's own contract says it sits behind the same gate
    // as ingest, and until this test existed nothing held it there.
    verify(senders, never()).findAllByIsActiveTrue();
    verify(syncStates, never()).save(any());
  }

  private BiSmsSenderAllowlist sender(String bankCode, String senderId) {
    return BiSmsSenderAllowlist.builder()
        .id(UUID.randomUUID())
        .bankCode(bankCode)
        .senderId(senderId)
        .countryCode("LK")
        .isActive(Boolean.TRUE)
        .build();
  }

  private BiBankParserTemplate template(String bankCode, String messageType) {
    return BiBankParserTemplate.builder()
        .id(UUID.randomUUID())
        .bankCode(bankCode)
        .messageType(messageType)
        .pattern("(?<amount>[0-9.,]+)")
        .fieldMap("{}")
        .templateVersion(1)
        .isActive(Boolean.TRUE)
        .build();
  }
}
