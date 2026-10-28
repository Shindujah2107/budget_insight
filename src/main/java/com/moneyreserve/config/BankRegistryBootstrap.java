package com.moneyreserve.config;

import com.moneyreserve.domain.BiBank;
import com.moneyreserve.repository.BiBankRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the bank registry from {@code moneyreserve.insights.admin.banks} when — and only when —
 * {@code bi_bank} is empty.
 *
 * <p>In every Flyway-managed environment V1.6.0 has already seeded the table, so this does nothing:
 * from then on the registry, edited from the Admin Dashboard, is the source of truth and the
 * property is never read again. It exists for profiles that build the schema without Flyway (the
 * H2 test and local profiles), which would otherwise start with no banks at all.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BankRegistryBootstrap implements ApplicationRunner {

  private final BiBankRepository banks;
  private final InsightsProperties properties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!properties.isEnabled() || banks.count() > 0) {
      return;
    }
    OffsetDateTime now = OffsetDateTime.now();
    Map<String, String> seed = properties.getAdmin().getBanks();
    seed.forEach(
        (code, name) ->
            banks.save(
                BiBank.builder().bankCode(code).bankName(name).isActive(true).createdAt(now).build()));
    log.info("Bank registry was empty; seeded {} banks from configuration", seed.size());
  }
}
