-- Runs outside a transaction: see V1.5.1__admin_read_indexes.sql.conf (executeInTransaction=false).
-- =====================================================================================
-- Budget Insights admin dashboard: indexes for the Overview and the Configuration usage
-- counts. Every Overview number is a COUNT / GROUP BY over existing rows; these keep those
-- queries off full scans of bi_transaction.
--
-- CREATE INDEX CONCURRENTLY does not block writes to bi_transaction, so this is safe in
-- staging, UAT and production (architecture doc §2.6). It cannot run inside a transaction,
-- hence executeInTransaction=false in the .sql.conf file beside it, and it waits for every open transaction to finish,
-- so Flyway must not hold its own lock in a transaction while it runs:
-- spring.flyway.postgresql.transactional-lock=false (set in application.yml).
--
-- IF NOT EXISTS keeps it idempotent. If a CONCURRENTLY build is interrupted it leaves an
-- INVALID index with the same name; drop it (DROP INDEX CONCURRENTLY ...) and re-run.
-- =====================================================================================
SET lock_timeout = '3s';

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bi_txn_date
    ON budget_insights.bi_transaction (txn_date) WHERE deleted_at IS NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bi_txn_template
    ON budget_insights.bi_transaction (parser_template_id) WHERE deleted_at IS NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bi_txn_category
    ON budget_insights.bi_transaction (category_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bi_bcl_category
    ON budget_insights.bi_budget_category_limit (category_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_bi_mcm_category
    ON budget_insights.bi_merchant_category_map (category_id);
