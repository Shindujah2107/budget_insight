package com.moipay.budgetinsights.config;

import com.moipay.budgetinsights.service.jobs.JobLock;
import com.moipay.budgetinsights.service.jobs.PostgresAdvisoryJobLock;
import com.moipay.budgetinsights.service.jobs.SingleInstanceJobLock;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Chooses the {@link JobLock} implementation from the database actually connected.
 *
 * <p>Not from a profile and not from a property. A property can be set to the wrong value and a
 * profile can be forgotten; the database either understands {@code pg_try_advisory_xact_lock} or it
 * does not, and it is the only thing that knows. Asking it once at startup costs one connection and
 * removes a class of configuration mistake that would otherwise surface as a job silently running
 * twice in production.
 *
 * <p><b>A metadata read that fails stops the service rather than guessing.</b> The tempting
 * alternative is to fall back to the single-instance lock, on the grounds that a wrong guess
 * towards PostgreSQL would fail every job with a syntax error while the other way still runs. That
 * reasoning is wrong, and the direction of the mistake is what makes it wrong: a PostgreSQL
 * deployment whose metadata read happened to fail — a momentary pool exhaustion at startup, a
 * network blip — would come up permanently unguarded, running every job on both tasks, with one
 * WARN line in a startup log nobody reads. The syntax error is loud and immediate; the silent
 * downgrade is the failure this whole class exists to prevent.
 *
 * <p>The availability argument for falling back is hollow in any case. This bean depends on the
 * {@code EntityManagerFactory}, which Hibernate has already built by reading JDBC metadata over a
 * connection of its own. If that succeeded, this will; if it did not, the context has already
 * failed and never reaches here.
 */
@Slf4j
@Configuration
public class JobLockConfig {

  /**
   * The {@code EntityManager} is built from the factory here rather than injected. Spring Data
   * registers a shared, transaction-aware one, so injecting it would also work — but that bean
   * exists as a side effect of repository scanning, and this configuration should not depend on
   * something it does not name. {@link SharedEntityManagerCreator} gives the same proxy: one that
   * resolves to the {@code EntityManager} of whatever transaction the calling thread is in, which
   * is the whole point, because the lock must be taken on the connection that the job's
   * transaction is using.
   */
  @Bean
  public JobLock jobLock(DataSource dataSource, EntityManagerFactory entityManagerFactory) {
    String product = databaseProductName(dataSource);

    if (product.toLowerCase(Locale.ROOT).contains("postgresql")) {
      log.info("Insights scheduled jobs are guarded by PostgreSQL advisory locks ({})", product);
      return new PostgresAdvisoryJobLock(
          SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    log.info(
        "Database reports itself as '{}', not PostgreSQL. Scheduled jobs will be guarded within "
            + "this JVM only and will run on every instance. Expected under H2 in tests and local "
            + "development; in a deployed environment this means the jobs are unguarded.",
        product);
    return new SingleInstanceJobLock();
  }

  private String databaseProductName(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      String product = connection.getMetaData().getDatabaseProductName();
      if (product == null || product.isBlank()) {
        throw new IllegalStateException(
            "The database did not report a product name, so the scheduled-job lock cannot be "
                + "chosen. Refusing to start rather than running jobs unguarded.");
      }
      return product;
    } catch (SQLException ex) {
      throw new IllegalStateException(
          "Could not read the database product name, so the scheduled-job lock cannot be chosen. "
              + "Refusing to start rather than running jobs unguarded.",
          ex);
    }
  }
}
