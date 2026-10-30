package com.moneyreserve.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Where the format-discovery floor may be lowered. */
class FormatDiscoveryLocalGuardTest {

  private static InsightsProperties insights(Integer localMin, int min) {
    InsightsProperties insights = new InsightsProperties();
    insights.getFormatDiscovery().setLocalMinDistinctUsers(localMin);
    insights.getFormatDiscovery().setMinDistinctUsers(min);
    return insights;
  }

  private static void verify(InsightsProperties insights, String... profiles) {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    new FormatDiscoveryLocalGuard(insights, env).verify();
  }

  @Test
  @DisplayName("a local floor runs on local and test only")
  void localOnly() {
    assertDoesNotThrow(() -> verify(insights(1, 5), "local"));
    assertDoesNotThrow(() -> verify(insights(1, 5), "test"));
    assertThrows(IllegalStateException.class, () -> verify(insights(1, 5), "prod"));
    assertThrows(IllegalStateException.class, () -> verify(insights(1, 5), "local", "prod"));
    assertThrows(IllegalStateException.class, () -> verify(insights(1, 5)));
  }

  @Test
  @DisplayName("without it nothing changes anywhere, and the floor never drops below five")
  void unsetIsTheNormalFloor() {
    assertDoesNotThrow(() -> verify(insights(null, 5), "prod"));
    assertEquals(5, insights(null, 2).getFormatDiscovery().effectiveMinDistinctUsers());
    assertEquals(8, insights(null, 8).getFormatDiscovery().effectiveMinDistinctUsers());
    assertEquals(1, insights(1, 5).getFormatDiscovery().effectiveMinDistinctUsers());
  }
}
