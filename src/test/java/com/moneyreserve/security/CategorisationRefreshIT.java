package com.moneyreserve.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiMerchantCategoryMap;
import com.moneyreserve.domain.MatchType;
import com.moneyreserve.repository.BiCategoryRepository;
import com.moneyreserve.repository.BiMerchantCategoryMapRepository;
import com.moneyreserve.service.CategorisationService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

/**
 * The categoriser uses published configuration only (architecture doc §5.3 step 6): a drafted
 * category deactivation that moves its merchant rules changes nothing until publish, and the next
 * categorisation after publish lands on the new category.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Categoriser picks up a publish")
class CategorisationRefreshIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private BiCategoryRepository categories;
  @Autowired private BiMerchantCategoryMapRepository rules;
  @Autowired private CategorisationService categorisation;

  private BiCategory category(String code, int order) {
    return categories.save(
        BiCategory.builder()
            .id(UUID.randomUUID())
            .code(code)
            .nameEn(code)
            .iconKey("ic_shopping")
            .sortOrder((short) order)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build());
  }

  @Test
  @DisplayName("moved rules categorise to the new category only after publish")
  void movedRulesApplyAfterPublish() throws Exception {
    AdminTestSupport admin = new AdminTestSupport(rest, UUID.randomUUID());
    admin.ensureOther();
    long n = System.nanoTime() % 100000;
    BiCategory from = category("REFRESH_FROM_" + n, 70);
    BiCategory to = category("REFRESH_TO_" + n, 71);
    String merchant = "REFRESHMART" + n;
    rules.save(
        BiMerchantCategoryMap.builder()
            .id(UUID.randomUUID())
            .merchantPattern(merchant)
            .matchType(MatchType.EXACT)
            .categoryId(from.getId())
            .priority((short) 5)
            .isActive(true)
            .createdAt(OffsetDateTime.now())
            .build());
    assertEquals(from.getId(), categorisation.categorise(merchant).getId());

    admin.config(
        HttpMethod.POST,
        "/categories/" + from.getId() + "/deactivate",
        "{\"moveMerchantRulesTo\":\"" + to.getId() + "\"}");
    assertEquals(
        from.getId(), categorisation.categorise(merchant).getId(), "a draft must not be live");

    var published = admin.publishAll();
    assertEquals(201, published.getStatusCode().value(), published.getBody());
    assertEquals(to.getId(), categorisation.categorise(merchant).getId());
  }
}
