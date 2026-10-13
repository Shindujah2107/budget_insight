package com.moipay.budgetinsights.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything the device needs to read bank messages on its own (BI-13).
 *
 * <p>The allowlist decides which senders are looked at; the templates decide how their messages are
 * read. Both are served rather than shipped, so adding a bank or fixing a pattern after a bank
 * changes its alert format is a data change.
 *
 * <p>{@code templateVersion} is the cache validator. A device holds it, sends it up with every
 * ingest batch, and re-syncs when the two differ — which is cheaper than re-fetching the whole
 * reference set on a timer, and more prompt than waiting for one.
 *
 * <p><b>Empty lists are a valid, expected answer today.</b> The allowlist and the templates are
 * deliberately unseeded until the labelled SMS sample set arrives (dependency D-1): seeding them
 * with invented sender tags and guessed patterns would produce a parser whose accuracy nobody could
 * evidence, on the one path where accuracy is a compliance matter. A device that receives empty
 * lists reads no messages, which is the correct behaviour until there is something real to read
 * them with.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReferenceSyncResponse {

  /** Highest active template version on the server. The device's cache validator. */
  private int templateVersion;

  /** True when the caller's stated version already matches. The payload is served either way. */
  private boolean upToDate;

  private List<SenderAllowlistDto> senders;

  private List<ParserTemplateDto> templates;

  /**
   * SMS format discovery: the words a phone may name in a skeleton. Present only when the phone
   * sent a {@code vocabularyVersion} older than the server's and Phase 2 is on, so the response to
   * every existing app version is unchanged.
   */
  private SmsVocabularyDto vocabulary;

  /** When this device last synced, before this call. Absent on a device's first sync. */
  private OffsetDateTime previousSyncedAt;

  private OffsetDateTime syncedAt;
}
