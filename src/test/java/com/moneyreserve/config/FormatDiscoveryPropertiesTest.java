package com.moneyreserve.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How many customers must report a format before admins see it. */
class FormatDiscoveryPropertiesTest {

  private static int floor(Integer setting) {
    InsightsProperties insights = new InsightsProperties();
    if (setting != null) {
      insights.getFormatDiscovery().setMinDistinctUsers(setting);
    }
    return insights.getFormatDiscovery().effectiveMinDistinctUsers();
  }

  @Test
  @DisplayName("one report is enough by default; the setting can raise it but never below 1")
  void floor() {
    assertEquals(1, floor(null));
    assertEquals(5, floor(5));
    assertEquals(1, floor(0));
    assertEquals(1, floor(-3));
  }
}
