package com.moneyreserve;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MoneyReserve API: sign-in, and Budget Insights (spending from bank SMS, budgets and alerts) for
 * the MoneyReserve app. Mobile app -> this service -> its own PostgreSQL database.
 *
 * <p>There is deliberately no {@code @EnableJpaAuditing} here: no entity in
 * this service uses Spring Data auditing annotations. Timestamps are set in {@code @PrePersist} and
 * {@code @PreUpdate}, so switching auditing on would be an inert annotation inviting the reader to
 * look for something that is not there.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MoneyReserveApplication {
  public static void main(String[] args) {
    SpringApplication.run(MoneyReserveApplication.class, args);
  }
}
