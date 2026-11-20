package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyreserve.config.DemoBillsSeeder;
import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiBiller;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BillType;
import com.moneyreserve.domain.BudgetAlertType;
import com.moneyreserve.repository.BiAppUserRepository;
import com.moneyreserve.repository.BiBillerRepository;
import com.moneyreserve.repository.BiBudgetAlertRepository;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.service.BillService;
import com.moneyreserve.service.InsightsTransactionService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The local demo account: its three months of payments make the bills radar show the four known
 * billers plus the gym, with a jump on the latest electricity bill; seeding twice stores nothing
 * twice. (The bean itself only exists on the local profile with the flag on, so it is built here.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights: local demo bills account")
class DemoBillsSeederIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private ObjectMapper json;
  @Autowired private BiAppUserRepository users;
  @Autowired private InsightsTransactionService transactions;
  @Autowired private BillService bills;
  @Autowired private InsightsProperties properties;
  @Autowired private BiBillerRepository billers;
  @Autowired private BiBudgetAlertRepository alerts;
  @Autowired private BiCategoryRepository categories;

  @BeforeEach
  void seedReferenceData() {
    if (categories.findByCodeAndIsActiveTrue(BiCategory.OTHER_CODE).isEmpty()) {
      categories.save(
          BiCategory.builder()
              .id(UUID.randomUUID())
              .code(BiCategory.OTHER_CODE)
              .nameEn("Other")
              .iconKey("ic_other")
              .sortOrder((short) 17)
              .isActive(true)
              .createdAt(OffsetDateTime.now())
              .build());
    }
    biller("DIALOG", "Dialog", BillType.TELECOM, "DIALOG");
    biller("CEB", "CEB electricity", BillType.ELECTRICITY, "CEB");
    biller("NWSDB", "Water board", BillType.WATER, "NWSDB");
    biller("NETFLIX", "Netflix", BillType.STREAMING, "NETFLIX");
  }

  private void biller(String code, String name, BillType type, String terms) {
    if (billers.findByCode(code).isEmpty()) {
      billers.save(
          BiBiller.builder()
              .id(UUID.randomUUID())
              .code(code)
              .nameEn(name)
              .billType(type)
              .matchTerms(terms)
              .iconKey("ic_bills_utilities")
              .sortOrder((short) 10)
              .isActive(true)
              .createdAt(OffsetDateTime.now())
              .build());
    }
  }

  @Test
  @DisplayName("shows five suggested recurring payments (not bills until tracked), and seeding twice stores nothing twice")
  void demoAccountShowsTheRadar() throws Exception {
    DemoBillsSeeder seeder = new DemoBillsSeeder(users, transactions, bills, properties);
    seeder.run(null);
    seeder.run(null);

    UUID demo = users.findByPhone(DemoBillsSeeder.DEMO_PHONE).orElseThrow().getId();
    HttpHeaders h = new HttpHeaders();
    h.setBearerAuth(TestTokens.bearer(demo));
    ResponseEntity<String> r =
        rest.exchange("/api/insights/bills", HttpMethod.GET, new HttpEntity<>(h), String.class);
    assertEquals(200, r.getStatusCode().value(), r.getBody());

    Map<String, JsonNode> byName = new HashMap<>();
    assertEquals(0, json.readTree(r.getBody()).path("data").path("bills").size(), "suggestions are not added automatically");
    for (JsonNode b : json.readTree(r.getBody()).path("data").path("suggestions")) {
      byName.put(b.path("billerCode").asText(b.path("name").asText()), b);
    }
    assertEquals(5, byName.size(), byName.keySet().toString());
    assertTrue(byName.keySet().containsAll(java.util.List.of("DIALOG", "CEB", "NWSDB", "NETFLIX")));
    for (JsonNode b : byName.values()) {
      assertEquals(3, b.path("paymentCount").asInt(), "each payment stored once: " + b);
    }
    UUID ceb = UUID.fromString(byName.get("CEB").path("id").asText());
    assertEquals(0, alerts.findAllByBillIdAndAlertType(ceb, BudgetAlertType.BILL_JUMPED).size(), "a suggestion raises no alert");
  }
}
