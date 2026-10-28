package com.moneyreserve.security;

import static com.moneyreserve.security.AdminTestSupport.data;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * No customer data leaves the admin API (architecture doc §3.1, §5.5): every read response is
 * scanned for the customer's user id, amounts, account/card numbers and message text.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Budget Insights admin: no customer data in any response")
class AdminNoCustomerDataIT {

  private static final UUID CUSTOMER = UUID.fromString("c0ffee00-0000-4000-8000-00000000c001");
  private static final String MERCHANT = "PRIVATE MERCHANT 7788";
  private static final String AMOUNT = "987654.32";
  private static final Pattern FORBIDDEN_KEYS =
      Pattern.compile(
          "\"(amount|accountNumber|cardNumber|maskedTail|accountId|merchant|sms|smsText|rawMessage|sample)\"\\s*:",
          Pattern.CASE_INSENSITIVE);

  @Autowired private TestRestTemplate rest;
  @Autowired private BiTransactionRepository transactions;
  @Autowired private BiCategoryRepository categories;

  private AdminTestSupport admin;

  @BeforeEach
  void seed() throws Exception {
    admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    if (transactions.findById(CUSTOMER).isEmpty()) {
      UUID category =
          categories.findAll().stream()
              .findFirst()
              .map(BiCategory::getId)
              .orElseGet(
                  () ->
                      categories
                          .save(
                              BiCategory.builder()
                                  .id(UUID.randomUUID())
                                  .code("PRIVACY_TEST")
                                  .nameEn("Privacy test")
                                  .iconKey("ic_shopping")
                                  .sortOrder((short) 90)
                                  .isActive(true)
                                  .createdAt(OffsetDateTime.now())
                                  .build())
                          .getId());
      transactions.save(
          BiTransaction.builder()
              .id(CUSTOMER)
              .userId(CUSTOMER)
              .amount(new BigDecimal(AMOUNT))
              .currency("LKR")
              .direction(TxnDirection.DEBIT)
              .merchant(MERCHANT)
              .description("Card 1234 5678 9012 3456 purchase at " + MERCHANT)
              .txnDate(LocalDate.now())
              .categoryId(category)
              .isUserCategorised(false)
              .source(TxnSource.SMS_ANDROID)
              .idempotencyKey("privacy-" + CUSTOMER)
              .createdAt(OffsetDateTime.now())
              .build());
    }
  }

  @Test
  @DisplayName("every admin read carries counts and configuration only")
  void responsesHoldNoCustomerData() throws Exception {
    List<ResponseEntity<String>> responses = new ArrayList<>();
    responses.add(admin.call(HttpMethod.GET, "/api/insights/admin/overview", null));
    for (String path :
        List.of("/summary", "/banks", "/icon-keys", "/categories", "/senders", "/changeset",
            "/releases")) {
      responses.add(admin.config(HttpMethod.GET, path, null));
    }
    responses.add(admin.config(HttpMethod.POST, "/changeset/validate", null));
    for (var item : data(admin.config(HttpMethod.GET, "/categories", null)).path("items")) {
      responses.add(
          admin.config(HttpMethod.GET, "/categories/" + item.path("id").asText() + "/usage", null));
    }
    for (ResponseEntity<String> r : responses) {
      assertEquals(200, r.getStatusCode().value(), r.getBody());
      String body = r.getBody();
      assertFalse(body.contains(CUSTOMER.toString()), "customer id in " + body);
      assertFalse(body.contains(MERCHANT), "merchant text in " + body);
      assertFalse(body.contains(AMOUNT) || body.contains("987654"), "amount in " + body);
      assertFalse(body.contains("9012 3456"), "card number in " + body);
      assertFalse(FORBIDDEN_KEYS.matcher(body).find(), "forbidden field in " + body);
    }
    assertTrue(responses.size() >= 9);
  }
}
