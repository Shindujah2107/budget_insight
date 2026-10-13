package com.moipay.budgetinsights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MoiPay Budget Insights.
 *
 * <p>Migrated out of MoiPay-Backend/core (commit bdd15510, "MIP-8 | Budgetting Insights &
 * Analytics-foundation") into a standalone service, following the CaseEngine / FeeEngine /
 * SplitEngine pattern: Mobile -> API Gateway -> this service -> its own database.
 *
 * <p>There is deliberately no {@code @EnableJpaAuditing} here, unlike SplitEngine: no entity in
 * this service uses Spring Data auditing annotations. Timestamps are set in {@code @PrePersist} and
 * {@code @PreUpdate}, so switching auditing on would be an inert annotation inviting the reader to
 * look for something that is not there.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BudgetInsightsApplication {
  public static void main(String[] args) {
    SpringApplication.run(BudgetInsightsApplication.class, args);
  }
}
