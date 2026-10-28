package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BiBudgetAlert;
import com.moneyreserve.domain.BudgetAlertType;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Acknowledging an alert is a write, and it is the one write that takes an id a customer could
 * guess at.
 *
 * <p>The refusal to look for is <b>404, not 403</b>, for the same reason as everywhere else in
 * this service: another customer's alert is not a row this caller may not touch, it is a row their
 * queries cannot see. A 403 would confirm it exists.
 *
 * <p>The second assertion in each test is the one that matters. A refusal that set the timestamp
 * anyway would satisfy a status check and still have let one customer mark another's alert as
 * seen — invisible afterwards, because an acknowledged alert simply stops asking for attention.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Alert acknowledgement ownership")
class AlertOwnershipIT {


  @Autowired private TestRestTemplate rest;
  @Autowired private BiBudgetAlertRepository alerts;

  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();

  @Test
  @DisplayName("another customer's alert cannot be acknowledged, and is not acknowledged anyway")
  void anotherCustomerCannotAcknowledge() {
    UUID alertId = raiseAlertFor(alice);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/alerts/" + alertId + "/acknowledge",
            HttpMethod.POST,
            as(bob),
            String.class);

    assertEquals(404, response.getStatusCode().value(), "Bob reached Alice's alert");
    assertNull(
        alerts.findById(alertId).orElseThrow().getAcknowledgedAt(),
        "Bob's refused request marked Alice's alert as seen");
  }

  @Test
  @DisplayName("a customer can acknowledge their own alert")
  void theOwnerCanAcknowledge() {
    // The control. Without it the refusal above would pass against a route that refuses everyone.
    UUID alertId = raiseAlertFor(bob);

    ResponseEntity<String> response =
        rest.exchange(
            "/api/insights/alerts/" + alertId + "/acknowledge",
            HttpMethod.POST,
            as(bob),
            String.class);

    assertTrue(
        response.getStatusCode().is2xxSuccessful(),
        "Bob could not acknowledge his own alert: " + response.getStatusCode());
    assertTrue(
        alerts.findById(alertId).orElseThrow().getAcknowledgedAt() != null,
        "The acknowledgement answered 200 without recording anything");
  }

  // ---------------------------------------------------------------------------------

  private UUID raiseAlertFor(UUID user) {
    // Written straight to the table rather than provoked through the budget path: what is under
    // test is who may acknowledge one, not what raises one, and BudgetAlertServiceImplTest already
    // covers the raising.
    return alerts
        .save(
            BiBudgetAlert.builder()
                .id(UUID.randomUUID())
                .userId(user)
                .periodStart(LocalDate.now().withDayOfMonth(1))
                .alertType(BudgetAlertType.BUDGET_EXCEEDED)
                .amountAtRaise(new BigDecimal("120000.00"))
                .limitAtRaise(new BigDecimal("100000.00"))
                .raisedAt(OffsetDateTime.now())
                .build())
        .getId();
  }

  private HttpEntity<String> as(UUID user) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.setBearerAuth(TestTokens.bearer(user));
    return new HttpEntity<>(null, headers);
  }
}
