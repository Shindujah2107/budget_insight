package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One parser template: the pattern the device runs against a message body, and the map that says
 * which capture group is which field.
 *
 * <p><b>The parsing happens on the handset, not here.</b> That is the whole design: the message
 * body is matched and discarded on the device, and only the fields it yielded ever cross the wire.
 * Serving the templates from the server keeps them changeable — a bank that reformats its alerts
 * is a row update, not an app release waiting on two stores — without moving a single message body
 * to the server.
 *
 * <p>{@code id} is here because the device sends it back as {@code parserTemplateId} on each
 * ingested message, which is how per-bank parser accuracy gets measured at all once the labelled
 * sample set arrives.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ParserTemplateDto {

  private UUID id;

  private String bankCode;

  /** For example CARD_PURCHASE. Also what decides whether this message type is in scope. */
  private String messageType;

  /** The regular expression the device applies to the message body. */
  private String pattern;

  /** Raw JSON: capture group to field name. Passed through untouched. */
  private String fieldMap;

  private Integer templateVersion;
}
