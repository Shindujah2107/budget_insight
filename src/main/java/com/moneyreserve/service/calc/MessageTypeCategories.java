package com.moneyreserve.service.calc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Categorisation layer 0: message types decided by type alone, before any merchant rule (an ATM
 * receipt's "merchant" is the ATM's location, a CRM deposit's the branch, an account charge's the
 * fee text). Shared by the categoriser, the admin template preview and the uncategorised report so
 * all three agree.
 */
public final class MessageTypeCategories {

  /** The defaults, used when nothing is configured. */
  public static Map<String, String> defaults() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("ATM_RECEIPT", "OTHER");
    m.put("CRM_DEPOSIT", "OTHER");
    m.put("ACCOUNT_CHARGE", "OTHER");
    return m;
  }

  private MessageTypeCategories() {}

  /** The category code configured for a message type, if any. */
  public static Optional<String> codeFor(Map<String, String> configured, String messageType) {
    if (messageType == null || messageType.isBlank()) {
      return Optional.empty();
    }
    Map<String, String> map = configured == null ? defaults() : configured;
    String code = map.get(messageType.trim().toUpperCase(Locale.ROOT));
    return code == null || code.isBlank() ? Optional.empty() : Optional.of(code.trim());
  }

  /** Every message type decided by layer 0 (never empty, so it is safe in a JPQL {@code IN}). */
  public static List<String> types(Map<String, String> configured) {
    Map<String, String> map = configured == null ? defaults() : configured;
    List<String> out = map.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isBlank())
        .map(e -> e.getKey().trim().toUpperCase(Locale.ROOT))
        .toList();
    return out.isEmpty() ? List.of("-") : out;
  }
}
