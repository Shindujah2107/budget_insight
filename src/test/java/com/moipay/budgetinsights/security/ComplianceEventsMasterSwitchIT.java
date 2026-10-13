package com.moipay.budgetinsights.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The master switch outranks everything, including the route with no release flag of its own.
 *
 * <p>With Budget Insights off entirely the route answers 404, even though a salt is configured and
 * nothing else would have withheld it. That ordering is what lets the whole feature be withdrawn in
 * one setting, without having to reason about which capability underneath happens to be reachable.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "moipay.budget-insights.enabled=false")
@ActiveProfiles("test")
@DisplayName("Device compliance reporting — master switch off")
class ComplianceEventsMasterSwitchIT {

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("Reporting answers 404, not the unconfigured-route 501")
  void answers404() {
    ResponseEntity<String> response =
        rest.exchange(
            ComplianceEventsUnconfiguredIT.EVENTS,
            HttpMethod.POST,
            ComplianceEventsUnconfiguredIT.authedReport(),
            String.class);

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
  }
}
