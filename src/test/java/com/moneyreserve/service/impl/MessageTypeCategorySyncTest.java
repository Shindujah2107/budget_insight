package com.moneyreserve.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiMessageType;
import com.moneyreserve.repository.BiMessageTypeRepository;
import com.moneyreserve.service.calc.MessageTypeCategories;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Message type list → categorisation layer 0")
class MessageTypeCategorySyncTest {

  private static BiMessageType type(String code, String category) {
    return BiMessageType.builder().code(code).fixedCategoryCode(category).isActive(true).build();
  }

  /** A repository that only answers findAll (everything else is unused here). */
  private static BiMessageTypeRepository repo(List<BiMessageType> rows) {
    return (BiMessageTypeRepository)
        Proxy.newProxyInstance(
            BiMessageTypeRepository.class.getClassLoader(),
            new Class<?>[] {BiMessageTypeRepository.class},
            (p, m, a) -> {
              if (m.getName().equals("findAll") && (a == null || a.length == 0)) {
                return rows;
              }
              throw new UnsupportedOperationException(m.getName());
            });
  }

  @Test
  @DisplayName("the V1.15.0 seed (the three defaults) leaves the map exactly as configured")
  void seedChangesNothing() {
    InsightsProperties properties = new InsightsProperties();
    Map<String, String> before = Map.copyOf(properties.getCategorisation().getMessageTypeCategories());
    List<BiMessageType> rows = new ArrayList<>(List.of(
        type("ATM_RECEIPT", "OTHER"), type("CRM_DEPOSIT", "OTHER"), type("ACCOUNT_CHARGE", "OTHER"),
        type("CARD_PURCHASE", null), type("ACCOUNT_TXN", null)));
    new MessageTypeCategorySync(repo(rows), properties).refresh();
    assertEquals(before, properties.getCategorisation().getMessageTypeCategories());
    assertEquals(MessageTypeCategories.defaults(), properties.getCategorisation().getMessageTypeCategories());
  }

  @Test
  @DisplayName("an empty list (not set up yet) keeps the configured map")
  void emptyListKeepsConfig() {
    InsightsProperties properties = new InsightsProperties();
    new MessageTypeCategorySync(repo(List.of()), properties).refresh();
    assertEquals(MessageTypeCategories.defaults(), properties.getCategorisation().getMessageTypeCategories());
  }

  @Test
  @DisplayName("a listed type adds, overrides or removes its entry; unlisted configured types stay")
  void listOverridesConfig() {
    Map<String, String> configured = Map.of("ATM_RECEIPT", "OTHER", "CRM_DEPOSIT", "OTHER", "LEGACY", "TRANSPORT");
    Map<String, String> merged =
        MessageTypeCategorySync.merge(
            configured,
            List.of(type("ATM_RECEIPT", null), type("CRM_DEPOSIT", "TRANSFERS"), type("CARD_ALERT", " SHOPPING ")));
    assertEquals(Map.of("CRM_DEPOSIT", "TRANSFERS", "LEGACY", "TRANSPORT", "CARD_ALERT", "SHOPPING"), merged);
  }

  @Test
  @DisplayName("a change is picked up on the next refresh, and undone when the list is restored")
  void refreshFollowsTheList() {
    InsightsProperties properties = new InsightsProperties();
    List<BiMessageType> rows = new ArrayList<>(List.of(type("ATM_RECEIPT", "OTHER")));
    MessageTypeCategorySync sync = new MessageTypeCategorySync(repo(rows), properties);
    sync.refresh();
    rows.set(0, type("ATM_RECEIPT", null));
    sync.refresh();
    assertEquals(null, properties.getCategorisation().getMessageTypeCategories().get("ATM_RECEIPT"));
    rows.set(0, type("ATM_RECEIPT", "OTHER"));
    sync.refresh();
    assertEquals(MessageTypeCategories.defaults(), properties.getCategorisation().getMessageTypeCategories());
  }

  @Test
  @DisplayName("an unreadable list keeps the map in force")
  void unreadableListKeepsMap() {
    InsightsProperties properties = new InsightsProperties();
    BiMessageTypeRepository broken =
        (BiMessageTypeRepository)
            Proxy.newProxyInstance(
                BiMessageTypeRepository.class.getClassLoader(),
                new Class<?>[] {BiMessageTypeRepository.class},
                (p, m, a) -> {
                  throw new IllegalStateException("db down");
                });
    new MessageTypeCategorySync(broken, properties).refresh();
    assertEquals(MessageTypeCategories.defaults(), properties.getCategorisation().getMessageTypeCategories());
  }
}
